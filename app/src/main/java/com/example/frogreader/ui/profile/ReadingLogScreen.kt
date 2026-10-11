package com.example.frogreader.ui.profile

import com.example.frogreader.ui.components.FrogSnackbarHost
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import com.example.frogreader.ui.nav.FrogAddFab
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.ReadingLogDeletion
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.ui.nav.ReadingQueueFabState
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReadingLogScreen(
    onBack: () -> Unit, onOpenEntry: (String) -> Unit,
    onFabStateChange: ((ReadingQueueFabState?) -> Unit)? = null,
    onCaptureCover: () -> Unit = {}, capturedCoverUri: Uri? = null, onCapturedCoverConsumed: () -> Unit = {},
    editEntryId: String? = null, onEditRequestConsumed: () -> Unit = {},
    deletedReading: ReadingLogDeletion? = null, onDeletedReadingConsumed: () -> Unit = {},
) {
    val vm: ReadingLogViewModel = viewModel(factory = ReadingLogViewModel.Factory)
    val entries by vm.entries.collectAsStateWithLifecycle()
    val activeProgress by vm.activeProgress.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    var navigationEditId by rememberSaveable { mutableStateOf<String?>(null) }
    val sortName by vm.sort.collectAsStateWithLifecycle()
    val sort = ReadingLogSort.entries.firstOrNull { it.name == sortName } ?: ReadingLogSort.FINISHED_NEWEST
    val added by vm.addedEntryId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::attachCover)
    LaunchedEffect(capturedCoverUri, editor != null, busy) {
        if (capturedCoverUri != null && editor != null && !busy) { vm.attachCover(capturedCoverUri); onCapturedCoverConsumed() }
    }
    LaunchedEffect(editEntryId, ready) {
        if (ready && editEntryId != null) {
            entries.firstOrNull { it.id == editEntryId }?.let { navigationEditId = it.id; vm.edit(it) }
            onEditRequestConsumed()
        }
    }
    LaunchedEffect(editor != null, editEntryId) {
        if (editor == null && editEntryId == null) navigationEditId = null
    }
    LaunchedEffect(vm, resources) {
        vm.events.collect { event -> launch {
            snackbar.currentSnackbarData?.dismiss()
            when (event) {
                ReadingLogEvent.Failed -> snackbar.showSnackbar(resources.getString(R.string.reading_log_action_failed))
                is ReadingLogEvent.Deleted -> {
                    val result = snackbar.showSnackbar(resources.getString(R.string.reading_log_deleted, event.entry.title),
                        resources.getString(R.string.quotes_bookmarks_undo), withDismissAction = true, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.undo(event) else vm.finishDelete(event)
                }
            }
        } }
    }
    LaunchedEffect(deletedReading, ready) {
        if (ready && deletedReading != null) {
            vm.announceDeletedReading(deletedReading)
            onDeletedReadingConsumed()
        }
    }
    ReadingLogContent(sortedReadingLog(entries, sort), ready, busy, editor, sort, snackbar, vm::coverFor,
        ReadingLogActions(onBack, { vm.edit() }, onOpenEntry, vm::edit, vm::change, vm::dismissEditor, vm::save,
            vm::delete, vm::setSort, { picker.launch(arrayOf("image/*")) }, onCaptureCover, vm::removeCover,
            vm::beginReorder, vm::move, vm::finishReorder, vm::moveAccessibly), added, vm::acknowledgeAddedEntry, onFabStateChange,
        editorOpenedByNavigation = editor != null && editor?.draft?.id == navigationEditId,
        editorRequestPending = editEntryId != null,
        activeProgress = activeProgress)
}

internal data class ReadingLogActions(
    val back: () -> Unit = {}, val add: () -> Unit = {}, val open: (String) -> Unit = {},
    val edit: (ReadingLogEntry) -> Unit = {}, val change: (ReadingLogEntry) -> Unit = {},
    val dismiss: () -> Unit = {}, val save: () -> Unit = {}, val delete: (ReadingLogEntry) -> Unit = {},
    val sort: (ReadingLogSort) -> Unit = {}, val gallery: () -> Unit = {}, val camera: () -> Unit = {}, val removeCover: () -> Unit = {},
    val beginDrag: (String) -> Boolean = { false }, val move: (String, Int) -> Unit = { _, _ -> },
    val endDrag: (Boolean) -> Unit = {}, val accessibleMove: (String, Int) -> Boolean = { _, _ -> false },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogContent(
    entries: List<ReadingLogEntry>, ready: Boolean, busy: Boolean, editor: ReadingLogEditor?, sort: ReadingLogSort,
    snackbar: SnackbarHostState, coverOf: (ReadingLogEntry) -> File? = { null }, actions: ReadingLogActions = ReadingLogActions(),
    addedEntryId: String? = null, onAddedEntryShown: (String) -> Unit = {}, onFabStateChange: ((ReadingQueueFabState?) -> Unit)? = null,
    initialFilter: ReadingLogFilter = ReadingLogFilter.ALL,
    editorOpenedByNavigation: Boolean = false,
    editorRequestPending: Boolean = false,
    activeProgress: Map<String, Float> = emptyMap(),
) {
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val drag = remember(list) { ReadingQueueDragState(list) }
    val currentActions by rememberUpdatedState(actions)
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    val visibleEntries = remember(entries, filter) { entries.filter(filter::includes) }
    val keys = visibleEntries.map { it.id }
    val currentKeys by rememberUpdatedState(keys)
    val currentEntries by rememberUpdatedState(visibleEntries)
    val currentAllEntries by rememberUpdatedState(entries)
    val currentSort by rememberUpdatedState(sort)
    val reorderableIds = remember(visibleEntries, sort) {
        if (sort == ReadingLogSort.MANUAL) visibleEntries.takeIf { it.size > 1 }.orEmpty().mapTo(mutableSetOf()) { it.id }
        else visibleEntries.groupBy { readingLogReorderGroup(it, sort) }.filter { (date, peers) -> date != null && peers.size > 1 }
            .values.flatten().mapTo(mutableSetOf()) { it.id }
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var sortMenu by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ReadingLogEntry?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = { focus.clearFocus(); keyboard?.hide(); actions.dismiss() }
    val close = { if (!busy && editor != null) { if (editor.dirty) discard = true else dismiss() } }
    val editorOpen = editor != null
    val pageProgress = remember { Animatable(if (editorOpen) 1f else 0f) }
    var retainedEditor by remember { mutableStateOf(editor) }
    val shownEditor = editor ?: retainedEditor
    SideEffect { if (editor != null) retainedEditor = editor }
    LaunchedEffect(editorOpen) {
        // A details → edit navigation already animates both routes. Do not run a second entrance.
        if (editorOpen && editorOpenedByNavigation) pageProgress.snapTo(1f)
        else pageProgress.animateTo(if (editorOpen) 1f else 0f,
            tween(if (editorOpen) ReadingLogPageEnterMillis else ReadingLogPageExitMillis, easing = FastOutSlowInEasing))
        if (!editorOpen) retainedEditor = null
    }
    val presentation = readingLogPagePresentation(editorOpen,
        if (editorOpen && editorOpenedByNavigation) 1f else pageProgress.value)
    val listBlocked = presentation.blocksList || editorRequestPending
    val enabled = ready && !busy && !listBlocked && drag.id == null && deleting == null
    // Do not recolor the list's controls underneath a fading page.
    val visuallyEnabled = ready && !busy && drag.id == null && deleting == null
    val fabCallback by rememberUpdatedState(onFabStateChange)
    val add = remember { { currentActions.add() } }
    val fabVisible = presentation.fabVisible && !editorRequestPending && drag.id == null && deleting == null && !sortMenu
    SideEffect { fabCallback?.invoke(ReadingQueueFabState(fabVisible, enabled, add)) }
    DisposableEffect(Unit) { onDispose { fabCallback?.invoke(null) } }
    LaunchedEffect(addedEntryId, entries, filter, listBlocked) {
        val id = addedEntryId ?: return@LaunchedEffect
        val added = entries.firstOrNull { it.id == id } ?: return@LaunchedEffect
        if (!filter.includes(added)) { filter = ReadingLogFilter.ALL; return@LaunchedEffect }
        val index = keys.indexOf(id)
        if (index >= 0 && !listBlocked) { withFrameNanos { }; list.animateScrollToItem(index + 1); onAddedEntryShown(id) }
    }
    LaunchedEffect(drag.id) {
        if (drag.id == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag.id != null) {
            val now = withFrameNanos { it }; val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.032f); previous = now
            constrainReadingLogDrag(drag, currentEntries, currentSort)
            drag.id?.let { id -> drag.targetIndex(currentKeys, 1)?.let { target ->
                val fullTarget = readingLogMoveTarget(currentAllEntries, currentEntries, currentSort, id, target)
                if (fullTarget != null) {
                    if (target + 1 == list.firstVisibleItemIndex || currentKeys.indexOf(id) + 1 == list.firstVisibleItemIndex)
                        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                    currentActions.move(id, fullTarget)
                }
            } }
            val scroll = drag.scrollSpeed(with(density) { 64.dp.toPx() }, with(density) { 750.dp.toPx() }) * seconds
            if (scroll != 0f) list.scrollBy(scroll)
        }
    }
    DisposableEffect(drag) { onDispose { if (drag.id != null) { drag.stop(); currentActions.endDrag(true) } } }
    BackHandler(busy || listBlocked || drag.id != null) {
        when {
            busy -> Unit
            drag.id != null -> { drag.stop(); actions.endDrag(true) }
            else -> close()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.graphicsLayer { alpha = 1f - presentation.progress }
            .then(if (listBlocked) Modifier.clearAndSetSemantics {} else Modifier), containerColor = colors.surface, topBar = {
            TopAppBar(title = { Text(stringResource(R.string.reading_log_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = actions.back, enabled = enabled) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface))
        }, snackbarHost = { if (!presentation.pageMounted) FrogSnackbarHost(snackbar) }, floatingActionButton = {
            // Keep snackbar clearance stable while either FAB is fading.
            Spacer(Modifier.size(60.dp))
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else LazyColumn(state = list, userScrollEnabled = enabled,
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item("log-summary") {
                        ReadingLogToolbar(entries, filter, sort, enabled,
                            onFilter = { filter = it; list.requestScrollToItem(0) }, onSort = { sortMenu = true },
                            visuallyEnabled = visuallyEnabled)
                    }
                    if (visibleEntries.isEmpty()) item("empty") {
                        Column(Modifier.fillMaxWidth().padding(24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.AutoStories, null, Modifier.size(44.dp), tint = colors.primary)
                            Text(stringResource(if (entries.isEmpty()) R.string.reading_log_empty else R.string.reading_log_filter_empty),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(if (entries.isEmpty()) R.string.reading_log_empty_hint else R.string.reading_log_filter_empty_hint),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            if (entries.isEmpty()) Button(onClick = add, enabled = enabled) {
                                Text(stringResource(R.string.reading_log_add))
                            } else TextButton(onClick = { filter = ReadingLogFilter.ALL }) { Text(stringResource(R.string.reading_log_show_all)) }
                        }
                    }
                    itemsIndexed(visibleEntries, key = { _, it -> it.id }) { index, entry ->
                        val held = drag.id == entry.id
                        val upTarget = readingLogMoveTarget(entries, visibleEntries, sort, entry.id, index - 1)
                        val downTarget = readingLogMoveTarget(entries, visibleEntries, sort, entry.id, index + 1)
                        val lift by animateFloatAsState(if (held) 1f else 0f, spring(stiffness = 650f), label = "Reading Log lift")
                        Box(Modifier.animateItem(placementSpec = if (held) null else spring(stiffness = 650f)).zIndex(if (held) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (held) drag.translation(entry.id) else 0f
                                scaleX = 1f + .012f * lift; scaleY = 1f + .012f * lift
                                shadowElevation = 8.dp.toPx() * lift; shape = RoundedCornerShape(16.dp)
                            }.animateContentSize()) {
                            ReadingLogSwipeCard(entry, enabled, busy, actions.delete) { cardEnabled ->
                            ReadingLogCard(entry, coverOf(entry), held, cardEnabled, entry.id in reorderableIds,
                                upTarget != null, downTarget != null,
                                { actions.open(entry.id) }, { actions.edit(entry) }, { deleting = entry },
                                { upTarget?.let { actions.accessibleMove(entry.id, it) } ?: false },
                                { downTarget?.let { actions.accessibleMove(entry.id, it) } ?: false },
                                { y -> if (actions.beginDrag(entry.id)) {
                                    if (drag.start(entry.id, y)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) else actions.endDrag(true)
                                } }, { if (drag.id == entry.id) drag.drag(it) }, { cancel -> if (drag.id == entry.id) { drag.stop(); actions.endDrag(cancel) } },
                                { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }?.offset ?: 0 },
                                progressPercent = readingLogProgressPercent(entry, activeProgress[entry.id]))
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (presentation.pageMounted && shownEditor != null) {
            ReadingLogPageLayer(presentation, fromFab = shownEditor.isNew) {
                ReadingLogEditorForm(shownEditor, busy,
                    coverOf(shownEditor.draft), actions, close, snackbar)
            }
        }
        if (onFabStateChange == null) Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp)) {
            FrogAddFab(expanded = true, visible = fabVisible, enabled = enabled, onClick = add)
        }
    }
    if (sortMenu) ModalBottomSheet(onDismissRequest = { sortMenu = false }, containerColor = colors.floatingSurface) {
        ReadingLogSortOptions(sort, reorderableIds.isNotEmpty(),
            select = { option ->
                sortMenu = false
                actions.sort(option)
            })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_queue_discard_title)) }, text = { Text(stringResource(R.string.reading_queue_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; dismiss() }) { Text(stringResource(R.string.reading_queue_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.reading_queue_keep_editing)) } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_log_delete_title)) }, text = { Text(stringResource(R.string.reading_log_delete_hint)) },
        confirmButton = { TextButton(onClick = { deleting = null; actions.delete(entry) }) { Text(stringResource(R.string.reading_queue_remove), color = colors.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_delete_cancel)) } }) }
}

/** Stop the lifted card at the visible edges of its remembered-date group. */
private fun constrainReadingLogDrag(drag: ReadingQueueDragState, entries: List<ReadingLogEntry>, sort: ReadingLogSort) {
    if (sort == ReadingLogSort.MANUAL) return
    val id = drag.id ?: return
    val entry = entries.firstOrNull { it.id == id } ?: return
    val group = readingLogReorderGroup(entry, sort) ?: return
    val peers = entries.filter { readingLogReorderGroup(it, sort) == group }
    val visible = drag.list.layoutInfo.visibleItemsInfo
    val held = visible.firstOrNull { it.key == id } ?: return
    // A swap updates the entries before LazyColumn has placed them. Wait for
    // that layout before using the new group's first and last cards as bounds.
    if (held.index != entries.indexOfFirst { it.id == id } + 1) return
    val first = visible.firstOrNull { it.key == peers.firstOrNull()?.id }
    val last = visible.firstOrNull { it.key == peers.lastOrNull()?.id }
    val bounded = readingLogBoundedDragTop(drag.top, held.size,
        first?.let { it.offset + it.size / 2f }, last?.let { it.offset + it.size / 2f })
    if (bounded != drag.top) drag.drag(bounded - drag.top)
}

/** Permit crossing each peer's centre even when the dragged card is taller. */
internal fun readingLogBoundedDragTop(top: Float, heldHeight: Int, firstCenter: Float?, lastCenter: Float?): Float {
    val lower = firstCenter?.let { it - heldHeight / 2f - 1f } ?: Float.NEGATIVE_INFINITY
    val upper = lastCenter?.let { it - heldHeight / 2f + 1f } ?: Float.POSITIVE_INFINITY
    return if (lower <= upper) top.coerceIn(lower, upper) else top
}

internal fun sortLabel(sort: ReadingLogSort): Int = when (sort) {
    ReadingLogSort.FINISHED_NEWEST -> R.string.reading_log_finished_newest
    ReadingLogSort.FINISHED_OLDEST -> R.string.reading_log_finished_oldest
    ReadingLogSort.STARTED_NEWEST -> R.string.reading_log_started_newest
    ReadingLogSort.STARTED_OLDEST -> R.string.reading_log_started_oldest
    ReadingLogSort.MANUAL -> R.string.reading_log_manual
}
