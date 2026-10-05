package com.example.frogreader.ui.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.nav.FrogAddFab
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
) {
    val vm: ReadingLogViewModel = viewModel(factory = ReadingLogViewModel.Factory)
    val entries by vm.entries.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val sortName by vm.sort.collectAsStateWithLifecycle()
    val sort = ReadingLogSort.entries.firstOrNull { it.name == sortName } ?: ReadingLogSort.FINISHED_NEWEST
    val added by vm.addedEntryId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::attachCover)
    LaunchedEffect(capturedCoverUri, editor != null, busy) {
        if (capturedCoverUri != null && editor != null && !busy) { vm.attachCover(capturedCoverUri); onCapturedCoverConsumed() }
    }
    LaunchedEffect(editEntryId, ready) {
        if (ready && editEntryId != null) {
            entries.firstOrNull { it.id == editEntryId }?.let(vm::edit)
            onEditRequestConsumed()
        }
    }
    LaunchedEffect(vm) {
        vm.events.collect { event -> launch {
            snackbar.currentSnackbarData?.dismiss()
            when (event) {
                ReadingLogEvent.Failed -> snackbar.showSnackbar(context.getString(R.string.reading_log_action_failed))
                is ReadingLogEvent.Deleted -> {
                    val result = snackbar.showSnackbar(context.getString(R.string.reading_log_deleted, event.entry.title),
                        context.getString(R.string.quotes_bookmarks_undo), withDismissAction = true, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.undo(event) else vm.finishDelete(event)
                }
            }
        } }
    }
    ReadingLogContent(sortedReadingLog(entries, sort), ready, busy, editor, sort, snackbar, vm::coverFor,
        ReadingLogActions(onBack, { vm.edit() }, onOpenEntry, vm::edit, vm::change, vm::dismissEditor, vm::save,
            vm::delete, vm::setSort, { picker.launch(arrayOf("image/*")) }, onCaptureCover, vm::removeCover,
            vm::beginReorder, vm::move, vm::finishReorder, vm::moveAccessibly), added, vm::acknowledgeAddedEntry, onFabStateChange)
}

internal data class ReadingLogActions(
    val back: () -> Unit = {}, val add: () -> Unit = {}, val open: (String) -> Unit = {},
    val edit: (ReadingLogEntry) -> Unit = {}, val change: (ReadingLogEntry) -> Unit = {},
    val dismiss: () -> Unit = {}, val save: () -> Unit = {}, val delete: (ReadingLogEntry) -> Unit = {},
    val sort: (ReadingLogSort) -> Unit = {}, val gallery: () -> Unit = {}, val camera: () -> Unit = {}, val removeCover: () -> Unit = {},
    val beginDrag: () -> Boolean = { false }, val move: (String, Int) -> Unit = { _, _ -> },
    val endDrag: (Boolean) -> Unit = {}, val accessibleMove: (String, Int) -> Boolean = { _, _ -> false },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogContent(
    entries: List<ReadingLogEntry>, ready: Boolean, busy: Boolean, editor: ReadingLogEditor?, sort: ReadingLogSort,
    snackbar: SnackbarHostState, coverOf: (ReadingLogEntry) -> File? = { null }, actions: ReadingLogActions = ReadingLogActions(),
    addedEntryId: String? = null, onAddedEntryShown: (String) -> Unit = {}, onFabStateChange: ((ReadingQueueFabState?) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val drag = remember(list) { ReadingQueueDragState(list) }
    val currentActions by rememberUpdatedState(actions)
    val keys = entries.map { it.id }
    val currentKeys by rememberUpdatedState(keys)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var sortMenu by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ReadingLogEntry?>(null) }
    val close = { if (!busy) { if (editor?.dirty == true) discard = true else actions.dismiss() } }
    var shownEditor by remember { mutableStateOf(editor) }
    val currentEditor by rememberUpdatedState(editor)
    val currentBusy by rememberUpdatedState(busy)
    val keyboardEntrance = shownEditor?.isNew == true
    val sheetState = key(shownEditor != null) {
        rememberBottomSheetState(initialValue = if (keyboardEntrance) SheetValue.Expanded else SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded), confirmValueChange = {
                if (it != SheetValue.Hidden || currentEditor == null) true
                else if (currentBusy) false
                else if (currentEditor?.dirty == true) { discard = true; false } else true
            })
    }
    val entrance = remember(sheetState) { ReadingQueueSheetEntrance() }
    LaunchedEffect(editor) {
        if (editor != null) shownEditor = editor
        else if (shownEditor != null) { sheetState.hide(); shownEditor = null }
    }
    val closing = sheetState.currentValue != SheetValue.Hidden && sheetState.targetValue == SheetValue.Hidden
    val blocksControls = editor != null && !closing
    val enabled = ready && !busy && !blocksControls && drag.id == null && deleting == null
    val fabBoundary = LocalWindowInfo.current.containerSize.height - with(density) { 100.dp.toPx() }
    val coversFab by remember(sheetState, fabBoundary) { derivedStateOf { (runCatching { sheetState.requireOffset() }.getOrNull() ?: 0f) < fabBoundary } }
    val fabVisible = !blocksControls && (shownEditor == null || !coversFab) && drag.id == null && deleting == null
    val fabCallback by rememberUpdatedState(onFabStateChange)
    val add = remember { { currentActions.add() } }
    SideEffect { fabCallback?.invoke(ReadingQueueFabState(fabVisible, enabled, add)) }
    DisposableEffect(Unit) { onDispose { fabCallback?.invoke(null) } }
    LaunchedEffect(addedEntryId, keys, editor != null, shownEditor != null) {
        val id = addedEntryId ?: return@LaunchedEffect
        val index = keys.indexOf(id)
        if (index >= 0 && editor == null && shownEditor == null) { withFrameNanos { }; list.animateScrollToItem(index + 1); onAddedEntryShown(id) }
    }
    LaunchedEffect(drag.id) {
        if (drag.id == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag.id != null) {
            val now = withFrameNanos { it }; val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.032f); previous = now
            drag.id?.let { id -> drag.targetIndex(currentKeys, 1)?.let { target ->
                if (target + 1 == list.firstVisibleItemIndex || currentKeys.indexOf(id) + 1 == list.firstVisibleItemIndex)
                    list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                currentActions.move(id, target)
            } }
            val scroll = drag.scrollSpeed(with(density) { 64.dp.toPx() }, with(density) { 750.dp.toPx() }) * seconds
            if (scroll != 0f) list.scrollBy(scroll)
        }
    }
    DisposableEffect(drag) { onDispose { if (drag.id != null) { drag.stop(); currentActions.endDrag(true) } } }
    BackHandler(busy || editor != null || drag.id != null) {
        when { busy -> Unit; drag.id != null -> { drag.stop(); actions.endDrag(true) }; else -> close() }
    }
    Scaffold(containerColor = colors.surface, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.reading_log_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onClick = actions.back, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_back)) } },
            actions = { Box {
                IconButton(onClick = { sortMenu = true }, enabled = enabled) { Icon(Icons.Rounded.Sort, stringResource(R.string.reading_log_sort)) }
                DropdownMenu(sortMenu, { sortMenu = false }, containerColor = colors.floatingSurface) {
                    ReadingLogSort.entries.forEach { option -> DropdownMenuItem(text = { Text(stringResource(sortLabel(option))) },
                        trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) },
                        onClick = { sortMenu = false; actions.sort(option) }) }
                }
            } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface))
    }, snackbarHost = { if (shownEditor == null) SnackbarHost(snackbar) }, floatingActionButton = {
        if (onFabStateChange == null) FrogAddFab(expanded = true, visible = fabVisible, enabled = enabled, onClick = add)
        else if (fabVisible) Spacer(Modifier.size(60.dp))
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else LazyColumn(state = list, userScrollEnabled = drag.id == null, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item("log-summary") {
                    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(sortLabel(sort)), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                            Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                        }
                        Text(stringResource(if (sort == ReadingLogSort.MANUAL) R.string.reading_log_manual_hint else R.string.reading_log_dates_hint),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (entries.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.AutoStories, null, Modifier.size(44.dp), tint = colors.primary)
                        Text(stringResource(R.string.reading_log_empty), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.reading_log_empty_hint), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                itemsIndexed(entries, key = { _, it -> it.id }) { index, entry ->
                    val held = drag.id == entry.id
                    val lift by animateFloatAsState(if (held) 1f else 0f, spring(stiffness = 650f), label = "Reading Log lift")
                    Box(Modifier.animateItem(placementSpec = if (held) null else spring(stiffness = 650f)).zIndex(if (held) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (held) drag.translation(entry.id) else 0f
                            scaleX = 1f + .012f * lift; scaleY = 1f + .012f * lift
                            shadowElevation = 8.dp.toPx() * lift; shape = RoundedCornerShape(24.dp)
                        }.animateContentSize()) {
                        ReadingLogCard(entry, coverOf(entry), held, enabled, sort == ReadingLogSort.MANUAL, index, entries.size,
                            { actions.open(entry.id) }, { actions.edit(entry) }, { deleting = entry },
                            { actions.accessibleMove(entry.id, index - 1) }, { actions.accessibleMove(entry.id, index + 1) },
                            { y -> if (actions.beginDrag()) {
                                if (drag.start(entry.id, y)) haptic.performHapticFeedback(HapticFeedbackType.LongPress) else actions.endDrag(true)
                            } }, { if (drag.id == entry.id) drag.drag(it) }, { cancel -> if (drag.id == entry.id) { drag.stop(); actions.endDrag(cancel) } },
                            { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }?.offset ?: 0 })
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    shownEditor?.let { shown -> ModalBottomSheet(onDismissRequest = close, sheetState = sheetState, containerColor = colors.floatingSurface,
        sheetGesturesEnabled = !keyboardEntrance || entrance.finished,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).imePadding()
            .then(if (keyboardEntrance) Modifier.drawWithContent { if (entrance.visible) drawContent() } else Modifier),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) }) {
        if (keyboardEntrance) BindReadingQueueSheetEntrance(entrance, editor == null || closing)
        SnackbarHost(snackbar, Modifier.padding(horizontal = 20.dp))
        ReadingLogEditorForm(editor ?: shown, busy || editor == null, coverOf((editor ?: shown).draft), actions, close, editor != null && shown.isNew)
    } }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_queue_discard_title)) }, text = { Text(stringResource(R.string.reading_queue_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; actions.dismiss() }) { Text(stringResource(R.string.reading_queue_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.reading_queue_keep_editing)) } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_log_delete_title)) }, text = { Text(stringResource(R.string.reading_log_delete_hint)) },
        confirmButton = { TextButton(onClick = { deleting = null; actions.delete(entry) }) { Text(stringResource(R.string.reading_queue_remove), color = colors.error) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.library_delete_cancel)) } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingLogCard(entry: ReadingLogEntry, cover: File?, held: Boolean, enabled: Boolean, manual: Boolean, index: Int, count: Int,
    open: () -> Unit, edit: () -> Unit, delete: () -> Unit, up: () -> Boolean, down: () -> Boolean,
    start: (Float) -> Unit, move: (Float) -> Unit, stop: (Boolean) -> Unit, offset: () -> Int,
) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var cardTop by remember { mutableFloatStateOf(0f) }
    var handleTop by remember { mutableFloatStateOf(0f) }
    val currentStart by rememberUpdatedState(start); val currentMove by rememberUpdatedState(move); val currentStop by rememberUpdatedState(stop)
    val canDrag by rememberUpdatedState(enabled); val currentOffset by rememberUpdatedState(offset)
    val editLabel = stringResource(R.string.reading_log_edit)
    val upLabel = stringResource(R.string.reading_queue_move_up); val downLabel = stringResource(R.string.reading_queue_move_down)
    val dragLabel = stringResource(R.string.reading_queue_drag, entry.title)
    val book = remember(entry.title, entry.author) { Book(entry.id, entry.title, entry.author, BookFormat.EPUB, addedAtMillis = entry.createdAtMillis) }
    Surface(onClick = open, enabled = enabled || held, shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest,
        border = if (held) BorderStroke(1.dp, colors.primary) else null,
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { cardTop = it.positionInRoot().y }.semantics {
            customActions = buildList {
                if (enabled) {
                    add(CustomAccessibilityAction(editLabel) { edit(); true })
                    if (manual && index > 0) add(CustomAccessibilityAction(upLabel, up))
                    if (manual && index < count - 1) add(CustomAccessibilityAction(downLabel, down))
                }
            }
        }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.width(60.dp).height(90.dp).clip(RoundedCornerShape(9.dp))) { BookCover(book, cover, 9.sp, 5.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.title.uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.author.isNotBlank()) Text(entry.author.uppercase(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.series.isNotBlank()) Text(entry.series, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ReadingLogBadges(entry)
                }
                Column(Modifier.width(40.dp)) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.reading_queue_more), Modifier.size(20.dp)) }
                        DropdownMenu(menu, { menu = false }, containerColor = colors.floatingSurface) {
                            DropdownMenuItem(text = { Text(editLabel) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; edit() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_remove), color = colors.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) }, onClick = { menu = false; delete() })
                        }
                    }
                    if (manual) Box(Modifier.size(40.dp).onGloballyPositioned { handleTop = it.positionInRoot().y }
                        .semantics { contentDescription = dragLabel }.pointerInput(entry.id) {
                            detectDragGestures(onDragStart = { if (canDrag) currentStart(currentOffset() + handleTop - cardTop + it.y) },
                                onDragEnd = { currentStop(false) }, onDragCancel = { currentStop(true) }, onDrag = { change, amount -> change.consume(); currentMove(amount.y) })
                        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.DragIndicator, null, Modifier.size(22.dp), tint = if (held) colors.primary else colors.onSurfaceVariant) }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(readingLogDateRange(entry), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                entry.ratingHalfStars?.let { ReadingLogStars(it, iconSize = 15.dp) }
            }
            if (entry.review.isNotBlank()) Text(entry.review.replace(Regex("\\s+"), " "), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun sortLabel(sort: ReadingLogSort): Int = when (sort) {
    ReadingLogSort.FINISHED_NEWEST -> R.string.reading_log_finished_newest
    ReadingLogSort.FINISHED_OLDEST -> R.string.reading_log_finished_oldest
    ReadingLogSort.STARTED_NEWEST -> R.string.reading_log_started_newest
    ReadingLogSort.STARTED_OLDEST -> R.string.reading_log_started_oldest
    ReadingLogSort.MANUAL -> R.string.reading_log_manual
}
