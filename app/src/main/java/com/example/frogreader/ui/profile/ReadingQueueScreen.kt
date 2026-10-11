package com.example.frogreader.ui.profile

import com.example.frogreader.ui.components.FrogSnackbarHost
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.ui.library.BookCover
import com.example.frogreader.ui.nav.FrogAddFab
import com.example.frogreader.ui.nav.ReadingQueueFabState
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ReadingQueueScreen(onBack: () -> Unit, onOpenBook: (String) -> Unit,
    onFabStateChange: ((ReadingQueueFabState?) -> Unit)? = null,
) {
    val vm: ReadingQueueViewModel = viewModel(factory = ReadingQueueViewModel.Factory)
    val entries by vm.entries.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val addedEntryId by vm.addedEntryId.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val currentOpenBook by rememberUpdatedState(onOpenBook)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::attachFile)
    LaunchedEffect(vm, resources) {
        vm.events.collect { event ->
            when (event) {
                is ReadingQueueEvent.OpenBook -> currentOpenBook(event.id)
                is ReadingQueueEvent.Failed -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(resources.getString(event.message))
                }
                is ReadingQueueEvent.Deleted -> launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(resources.getString(R.string.reading_queue_deleted, event.entry.title),
                        resources.getString(R.string.quotes_bookmarks_undo), withDismissAction = true, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) vm.undo(event) else vm.finishDelete(event)
                }
            }
        }
    }
    ReadingQueueContent(entries, ready, busy, editor, snackbar, vm::coverFor,
        ReadingQueueActions(onBack, { vm.edit() }, vm::edit, vm::change, vm::dismissEditor, vm::save,
            vm::delete, { id ->
                vm.selectFileTarget(id)
                picker.launch(arrayOf("*/*"))
            }, vm::removeDraftFile, vm::startReading, vm::beginReorder, vm::move, vm::finishReorder, vm::moveAccessibly),
        addedEntryId = addedEntryId, onAddedEntryShown = vm::acknowledgeAddedEntry,
        onFabStateChange = onFabStateChange)
}

internal data class ReadingQueueActions(
    val back: () -> Unit = {},
    val add: () -> Unit = {},
    val edit: (ReadingQueueEntry) -> Unit = {},
    val change: (String?, String?, String?) -> Unit = { _, _, _ -> },
    val dismiss: () -> Unit = {},
    val save: () -> Unit = {},
    val delete: (ReadingQueueEntry) -> Unit = {},
    val pickFile: (String?) -> Unit = {},
    val removeDraftFile: () -> Unit = {},
    val startReading: (ReadingQueueEntry) -> Unit = {},
    val beginDrag: () -> Boolean = { false },
    val move: (String, Int) -> Unit = { _, _ -> },
    val endDrag: (Boolean) -> Unit = {},
    val accessibleMove: (String, Int) -> Boolean = { _, _ -> false },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReadingQueueContent(
    entries: List<ReadingQueueEntry>, ready: Boolean, busy: Boolean, editor: ReadingQueueEditor?,
    snackbar: SnackbarHostState, coverOf: (Book) -> File? = { null }, actions: ReadingQueueActions = ReadingQueueActions(),
    addedEntryId: String? = null, onAddedEntryShown: (String) -> Unit = {},
    onFabStateChange: ((ReadingQueueFabState?) -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    val drag = remember(list) { ReadingQueueDragState(list) }
    val haptics = LocalHapticFeedback.current
    val keys = remember(entries) { entries.map { it.id } }
    val currentKeys by rememberUpdatedState(keys)
    val currentActions by rememberUpdatedState(actions)
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val maxSpeed = with(density) { 750.dp.toPx() }
    var discard by rememberSaveable { mutableStateOf(false) }
    var filePromptId by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissalRequested by remember { mutableStateOf(false) }
    val dismiss = { dismissalRequested = true; actions.dismiss() }
    val closeEditor = { if (!busy) { if (editor?.dirty == true) discard = true else dismiss() } }
    var sheetEditor by remember { mutableStateOf(editor) }
    val mountedEditor = editor ?: sheetEditor
    val latestEditor by rememberUpdatedState(editor)
    val latestBusy by rememberUpdatedState(busy)
    val opensWithKeyboard = mountedEditor != null && mountedEditor.id == null
    val sheetState = key(mountedEditor != null) {
        // New entries stay at their live expanded anchor and reveal with the first IME frame.
        // A show spring would keep chasing the changing keyboard height. Existing entries
        // retain Material's normal entrance because they do not request the keyboard.
        rememberBottomSheetState(initialValue = if (opensWithKeyboard) SheetValue.Expanded else SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded), confirmValueChange = { value ->
            if (value != SheetValue.Hidden || latestEditor == null) true
            else if (latestBusy) false
            else if (latestEditor?.dirty == true) { discard = true; false }
            else { dismissalRequested = true; true }
        })
    }
    val sheetEntrance = remember(sheetState) { ReadingQueueSheetEntrance() }
    LaunchedEffect(editor) {
        if (editor != null) { sheetEditor = editor; dismissalRequested = false }
        else if (sheetEditor != null) { sheetState.hide(); sheetEditor = null; dismissalRequested = false }
    }
    val sheetPresentation = readingEntrySheetPresentation(editor != null, mountedEditor != null, dismissalRequested,
        sheetHiding = sheetState.currentValue != SheetValue.Hidden && sheetState.targetValue == SheetValue.Hidden)
    // The modal window still intercepts touches during its exit. Restore the controls'
    // appearance at the start of that exit rather than after its final animation frame.
    val controlsEnabled = !busy && !sheetPresentation.blocksInteraction && drag.id == null && filePromptId == null
    val fabVisible = !sheetPresentation.blocksAppearance && drag.id == null && filePromptId == null
    val addAction = remember { { currentActions.add() } }
    val currentFabCallback by rememberUpdatedState(onFabStateChange)
    SideEffect { currentFabCallback?.invoke(ReadingQueueFabState(fabVisible, ready && controlsEnabled, addAction)) }
    DisposableEffect(Unit) { onDispose { currentFabCallback?.invoke(null) } }
    // The queue label is the only non-book slot; drag indices refer only to books.
    val leadingItems = 1
    LaunchedEffect(addedEntryId, keys, busy, editor != null, sheetEditor != null) {
        val id = addedEntryId ?: return@LaunchedEffect
        val index = keys.indexOf(id)
        if (index >= 0 && !busy && editor == null && sheetEditor == null) {
            // Wait for the new row to be measured after closing the editor.
            withFrameNanos { }
            list.animateScrollToItem(index + leadingItems)
            onAddedEntryShown(id)
        }
    }
    LaunchedEffect(drag.id) {
        if (drag.id == null) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (drag.id != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(.032f)
            previous = now
            drag.id?.let { id ->
                drag.targetIndex(currentKeys, leadingItems)?.let { target ->
                    if (target + leadingItems == list.firstVisibleItemIndex ||
                        currentKeys.indexOf(id) + leadingItems == list.firstVisibleItemIndex) {
                        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                    }
                    currentActions.move(id, target)
                }
            }
            val scroll = drag.scrollSpeed(edge, maxSpeed) * seconds
            if (scroll != 0f) list.scrollBy(scroll)
        }
    }
    DisposableEffect(drag) {
        onDispose { if (drag.id != null) { drag.stop(); currentActions.endDrag(true) } }
    }
    BackHandler(busy || editor != null || drag.id != null) {
        when {
            busy -> Unit
            drag.id != null -> { drag.stop(); actions.endDrag(true) }
            else -> closeEditor()
        }
    }
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.profile_reading_queue), Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (ready) Surface(shape = RoundedCornerShape(10.dp), color = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer) {
                            Text(entries.size.toString(), Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = { if (editor != null) closeEditor() else actions.back() }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.reader_back))
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
            )
        },
        snackbarHost = { if (sheetEditor == null) FrogSnackbarHost(snackbar) },
        floatingActionButton = {
            if (onFabStateChange == null) FrogAddFab(
                expanded = true, visible = fabVisible, enabled = ready && controlsEnabled, onClick = addAction,
            )
            // The real FAB lives above navigation; keep snackbar/Undo clearance in this scaffold.
            else if (fabVisible) Spacer(Modifier.size(60.dp))
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (!ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else LazyColumn(
                state = list, modifier = Modifier.fillMaxSize(), userScrollEnabled = drag.id == null,
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("queue-label") {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.reading_queue_up_next), Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant,
                            letterSpacing = 1.sp)
                        Text(stringResource(R.string.reading_queue_gesture_hint), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                    }
                }
                if (entries.isEmpty() && editor == null) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(40.dp), tint = colors.primary)
                        Text(stringResource(R.string.reading_queue_empty), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.reading_queue_empty_hint), style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.onSurfaceVariant)
                    }
                }
                itemsIndexed(entries, key = { _, entry -> entry.id }, contentType = { _, _ -> "reading_intention" }) { index, entry ->
                    val held = drag.id == entry.id
                    val lift by animateFloatAsState(if (held) 1f else 0f, spring(stiffness = 650f), label = "card lift")
                    // Store the last painted viewport position without scheduling recomposition per frame.
                    val paintedTop = remember { floatArrayOf(Float.NaN) }
                    val releaseTop = remember(held) {
                        if (!held && paintedTop[0].isFinite()) Animatable(paintedTop[0]) else null
                    }
                    var returning by remember(releaseTop) { mutableStateOf(releaseTop != null) }
                    LaunchedEffect(returning, releaseTop) {
                        val animation = releaseTop ?: return@LaunchedEffect
                        if (!returning) return@LaunchedEffect
                        // The final slot can move on drop/cancellation or while scrolling. Animate in
                        // viewport coordinates so a new layout never adds a second positional jump.
                        snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }?.offset }
                            .filterNotNull().collectLatest { target ->
                                animation.animateTo(target.toFloat(), spring(stiffness = 650f))
                                returning = false
                            }
                    }
                    val swipe = remember(entry.id, entry.updatedAtMillis) {
                        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it * .4f })
                    }
                    var swipeHandled by remember(swipe) { mutableStateOf(false) }
                    LaunchedEffect(busy, swipe.settledValue) {
                        // A file prompt or failed action leaves the row present.
                        // Allow onDismiss to start its save before returning that row to place.
                        if (swipe.settledValue == SwipeToDismissBoxValue.Settled) {
                            swipeHandled = false
                        } else if (!busy) {
                            delay(150)
                            swipe.reset()
                        }
                    }
                    Box(Modifier.animateItem(placementSpec = if (held || returning) null else spring(stiffness = 650f))
                        .zIndex(if (held || returning || lift > 0f) 1f else 0f)
                        .graphicsLayer {
                            val slot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }?.offset
                            // Read the current layout in the layer phase. Even snap() in
                            // animateFloatAsState lags behind remeasurement by a frame during a swap.
                            translationY = when {
                                held -> drag.translation(entry.id)
                                returning && slot != null -> releaseTop!!.value - slot
                                else -> 0f
                            }
                            if (slot != null) paintedTop[0] = slot + translationY
                            scaleX = 1f + .012f * lift; scaleY = 1f + .012f * lift
                            shadowElevation = 8.dp.toPx() * lift; shape = RoundedCornerShape(24.dp)
                        }.animateContentSize(spring(stiffness = 650f))) {
                        SwipeToDismissBox(
                            state = swipe,
                            gesturesEnabled = controlsEnabled,
                            onDismiss = { direction ->
                                // Material may invoke onDismiss again when its callback changes during a save.
                                if (!swipeHandled && direction != SwipeToDismissBoxValue.Settled) {
                                    swipeHandled = true
                                    when (direction) {
                                        SwipeToDismissBoxValue.EndToStart -> actions.delete(entry)
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            if (entry.attachment?.fileName != null) actions.startReading(entry)
                                            else filePromptId = entry.id
                                        }
                                        SwipeToDismissBoxValue.Settled -> Unit
                                    }
                                }
                            },
                            backgroundContent = {
                                val reading = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                val foreground = if (reading) colors.onPrimaryContainer else colors.onErrorContainer
                                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                                    .background(if (reading) colors.primaryContainer else colors.errorContainer)
                                    .padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (reading) Arrangement.Start else Arrangement.End) {
                                    Icon(if (!reading) Icons.Rounded.DeleteOutline else if (entry.attachment?.fileName != null)
                                        Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.AttachFile, null, tint = foreground)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(if (!reading) R.string.reading_queue_remove else if (entry.attachment?.fileName != null)
                                        R.string.reading_queue_start else R.string.reading_queue_add_file), color = foreground,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            },
                        ) {
                            ReadingQueueCard(entry, index, entries.size, held, controlsEnabled, coverOf,
                                onEdit = { actions.edit(entry) }, onFile = { actions.pickFile(entry.id) },
                                onRead = { actions.startReading(entry) }, onDelete = { actions.delete(entry) },
                                onUp = { actions.accessibleMove(entry.id, index - 1) },
                                onDown = { actions.accessibleMove(entry.id, index + 1) },
                                onStart = { handleY ->
                                    if (actions.beginDrag()) {
                                        if (drag.start(entry.id, handleY, paintedTop[0].takeIf { it.isFinite() }))
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        else actions.endDrag(true)
                                    }
                                },
                                onDrag = { if (drag.id == entry.id) drag.drag(it) },
                                onStop = { cancel -> if (drag.id == entry.id) {
                                    drag.stop(); actions.endDrag(cancel)
                                    if (!cancel) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } },
                                rowOffset = { list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == entry.id }?.offset ?: 0 },
                            )
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
    mountedEditor?.let { displayedEditor -> ReadingEntrySheetMotion {
        ModalBottomSheet(
            onDismissRequest = closeEditor,
            sheetState = sheetState,
            containerColor = colors.floatingSurface,
            sheetGesturesEnabled = !opensWithKeyboard || sheetEntrance.finished,
            // Keep the top boundary independent of the sheet's animated offset.
            // IME padding belongs outside the Surface/anchors: the whole sheet follows
            // the keyboard insets rather than growing from padding inside its content.
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .imePadding()
                .then(if (opensWithKeyboard) Modifier.drawWithContent {
                    // Draw at full opacity without a compositing layer. A graphicsLayer
                    // with alpha < 1 would clip the Surface shifted by Material's anchors,
                    // producing a gap above the keyboard and cutting off the footer.
                    if (sheetEntrance.visible) drawContent()
                } else Modifier),
            contentWindowInsets = {
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            },
        ) {
            if (opensWithKeyboard) BindReadingQueueSheetEntrance(sheetEntrance,
                closing = sheetPresentation.closing)
            FrogSnackbarHost(snackbar)
            ReadingQueueEditorForm(editor ?: displayedEditor, busy || editor == null, actions, closeEditor,
                autoFocusTitle = editor != null && displayedEditor.id == null)
        }
    } }
    entries.firstOrNull { it.id == filePromptId }?.let { entry ->
        AlertDialog(onDismissRequest = { filePromptId = null }, containerColor = colors.floatingSurface,
            icon = { Icon(Icons.Rounded.AttachFile, null) },
            title = { Text(stringResource(R.string.reading_queue_add_file)) },
            text = { Text(stringResource(R.string.reading_queue_file_prompt, entry.title)) },
            confirmButton = { TextButton(onClick = { filePromptId = null; actions.pickFile(entry.id) }) {
                Text(stringResource(R.string.reading_queue_add_file))
            } },
            dismissButton = { TextButton(onClick = { filePromptId = null }) { Text(stringResource(R.string.library_delete_cancel)) } })
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_queue_discard_title)) },
        text = { Text(stringResource(R.string.reading_queue_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; dismiss() }) {
            Text(stringResource(R.string.reading_queue_discard))
        } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.reading_queue_keep_editing)) } },
    )
}

@Composable
private fun ReadingQueueCard(
    entry: ReadingQueueEntry, index: Int, count: Int, held: Boolean, enabled: Boolean, coverOf: (Book) -> File?,
    onEdit: () -> Unit, onFile: () -> Unit, onRead: () -> Unit, onDelete: () -> Unit,
    onUp: () -> Boolean, onDown: () -> Boolean,
    onStart: (Float) -> Unit, onDrag: (Float) -> Unit, onStop: (Boolean) -> Unit, rowOffset: () -> Int,
) {
    val colors = MaterialTheme.colorScheme
    val up = stringResource(R.string.reading_queue_move_up)
    val down = stringResource(R.string.reading_queue_move_down)
    val remove = stringResource(R.string.reading_queue_remove)
    val hasFile = entry.attachment?.fileName != null
    val readAction = stringResource(if (hasFile) R.string.reading_queue_start else R.string.reading_queue_add_file)
    val position = stringResource(R.string.reading_queue_position, index + 1, count)
    val dragLabel = stringResource(R.string.reading_queue_drag, entry.title)
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onDrag)
    val stop by rememberUpdatedState(onStop)
    val canStart by rememberUpdatedState(enabled)
    val offset by rememberUpdatedState(rowOffset)
    var cardTop by remember { mutableFloatStateOf(0f) }
    var handleTop by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val coverBook = remember(entry) { entry.attachment ?: Book(entry.id, entry.title, entry.author,
        BookFormat.EPUB, addedAtMillis = entry.createdAtMillis) }
    // Whitespace cannot turn a short note into a very tall card. The saved text stays untouched.
    val displayNote = remember(entry.note) { entry.note.replace(Regex("\\s+"), " ").trim() }
    Surface(
        onClick = onEdit, enabled = enabled || held,
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainerLowest,
        border = when { held -> BorderStroke(1.dp, colors.primary)
            index == 0 -> BorderStroke(1.dp, colors.primary.copy(alpha = .16f))
            else -> null },
        modifier = Modifier.fillMaxWidth().onGloballyPositioned { cardTop = it.positionInRoot().y }.semantics {
            stateDescription = position
            customActions = buildList {
                if (enabled) {
                    add(CustomAccessibilityAction(readAction) { if (hasFile) onRead() else onFile(); true })
                    if (index > 0) add(CustomAccessibilityAction(up, onUp))
                    if (index < count - 1) add(CustomAccessibilityAction(down, onDown))
                    add(CustomAccessibilityAction(remove) { onDelete(); true })
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(10.dp))) {
                    BookCover(coverBook, coverOf(coverBook), titleSize = 9.sp, padding = 6.dp)
                    if (hasFile) Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        shape = RoundedCornerShape(50), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                        Icon(Icons.Rounded.AttachFile, stringResource(R.string.reading_queue_file_ready, coverBook.format.name),
                            Modifier.padding(3.dp).size(14.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == 0) Surface(shape = RoundedCornerShape(50), color = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(13.dp))
                            Text(stringResource(R.string.reading_queue_first), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (entry.author.isNotBlank()) Text(entry.author, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Anchor both controls to the card's top edge, independently of title/note height.
                // Icons sit closer together inside separate, non-overlapping 48 dp touch targets.
                Row(Modifier.align(Alignment.Top)) {
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.reading_queue_more), Modifier.size(20.dp).offset(x = 4.dp))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.floatingSurface) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_edit)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text(readAction) },
                                leadingIcon = { Icon(if (hasFile) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.AttachFile, null) },
                                onClick = { menu = false; if (hasFile) onRead() else onFile() })
                            if (hasFile) DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_replace_file)) },
                                leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }, onClick = { menu = false; onFile() })
                            DropdownMenuItem(text = { Text(remove, color = colors.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) },
                                onClick = { menu = false; onDelete() })
                        }
                    }
                    Box(Modifier.size(48.dp).onGloballyPositioned { handleTop = it.positionInRoot().y }
                        .semantics { contentDescription = dragLabel }
                        .pointerInput(entry.id) {
                            detectDragGestures(
                                onDragStart = { point -> if (canStart) start(offset() + handleTop - cardTop + point.y) },
                                onDragEnd = { stop(false) }, onDragCancel = { stop(true) },
                                onDrag = { change, amount -> change.consume(); move(amount.y) },
                            )
                        }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragIndicator, null, Modifier.size(22.dp).offset(x = (-4).dp),
                            tint = if (held) colors.primary else colors.onSurfaceVariant.copy(alpha = .75f))
                    }
                }
            }
            if (displayNote.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(displayNote, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = if (entry.note.length <= ReadingQueueEntry.NOTE_LIMIT) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ReadingQueueEditorForm(
    editor: ReadingQueueEditor, busy: Boolean, actions: ReadingQueueActions, close: () -> Unit,
    autoFocusTitle: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val titleFocusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocusTitle) {
        // Request input immediately; the sheet's entrance follows the resulting IME frames.
        if (autoFocusTitle) {
            titleFocusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (editor.id == null) R.string.reading_queue_add else R.string.reading_queue_edit),
                Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FilledTonalIconButton(onClick = close, enabled = !busy) {
                Icon(Icons.Rounded.Close, stringResource(R.string.reading_queue_close))
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reading_queue_editor_hint),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            ReadingQueueEditorField(value = editor.title,
                onValueChange = { actions.change(it.take(ReadingQueueEntry.TITLE_LIMIT), null, null) },
                enabled = !busy, label = stringResource(R.string.reading_queue_title),
                icon = Icons.AutoMirrored.Rounded.MenuBook, modifier = Modifier.focusRequester(titleFocusRequester),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            ReadingQueueEditorField(value = editor.author,
                onValueChange = { actions.change(null, it.take(ReadingQueueEntry.AUTHOR_LIMIT), null) },
                enabled = !busy, label = stringResource(R.string.reading_queue_author), icon = Icons.Rounded.PersonOutline,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            ReadingQueueEditorField(value = editor.note, onValueChange = {
                // Reject additional characters without truncating an older, longer note on its first edit.
                if (it.length <= maxOf(ReadingQueueEntry.NOTE_LIMIT, editor.note.length)) actions.change(null, null, it)
            }, enabled = !busy, singleLine = false, minLines = 2, maxLines = 4,
                label = stringResource(R.string.reading_queue_note), icon = Icons.AutoMirrored.Rounded.Notes,
                errorMessage = if (!editor.noteValid)
                    pluralStringResource(R.plurals.reading_queue_legacy_note, ReadingQueueEntry.NOTE_LIMIT, ReadingQueueEntry.NOTE_LIMIT) else null,
                supportingText = {
                    Column {
                        if (editor.originalNote.length > ReadingQueueEntry.NOTE_LIMIT) Text(
                            pluralStringResource(R.plurals.reading_queue_legacy_note, ReadingQueueEntry.NOTE_LIMIT, ReadingQueueEntry.NOTE_LIMIT))
                        Text("${editor.note.length} / ${ReadingQueueEntry.NOTE_LIMIT}", Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (editor.failed) Text(stringResource(R.string.reading_queue_save_failed),
                color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
        // The sheet consumes the actual IME insets. Both actions are measured before the
        // weighted fields, so neither depends on the keyboard height or scroll position.
        ReadingQueueEditorActions(editor, busy, actions)
    }
}

@Composable
private fun ReadingQueueEditorField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    modifier: Modifier = Modifier, enabled: Boolean = true, singleLine: Boolean = true,
    minLines: Int = 1, maxLines: Int = 1, errorMessage: String? = null,
    supportingText: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = when {
        !enabled -> colors.onSurfaceVariant
        errorMessage != null -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(if (errorMessage != null) colors.error else colors.primary),
            interactionSource = interaction, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            modifier = modifier.fillMaxWidth().semantics {
                contentDescription = label
                if (errorMessage != null) error(errorMessage)
            },
            decorationBox = { input ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .background(colors.surfaceContainer, shape)
                    .border(1.5.dp, if (enabled && (focused || errorMessage != null)) accent else Color.Transparent, shape)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null, Modifier.size(24.dp), tint = accent)
                    Column(Modifier.weight(1f)) {
                        // Keep label, input and padding identical in every focus state. A floating
                        // Material label can briefly change the height and move the sheet anchor.
                        Text(label, Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.bodySmall, color = accent)
                        input()
                    }
                }
            })
        if (supportingText != null) {
            ProvideTextStyle(MaterialTheme.typography.bodySmall) {
                CompositionLocalProvider(LocalContentColor provides
                    if (errorMessage != null) colors.error else colors.onSurfaceVariant) {
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
                        supportingText()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingQueueEditorActions(editor: ReadingQueueEditor, busy: Boolean, actions: ReadingQueueActions) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val attached = editor.attachment?.takeIf { it.fileName != null }
    var attachmentMenu by remember(attached) { mutableStateOf(false) }
    val pickFile = { focus.clearFocus(); actions.pickFile(null) }
    val attachButton: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            OutlinedButton(
                onClick = { if (attached == null) pickFile() else attachmentMenu = true },
                enabled = !busy,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.AttachFile, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (attached == null) stringResource(R.string.reading_queue_add_file)
                    else stringResource(R.string.reading_queue_file_attached, attached.format.name))
            }
            DropdownMenu(expanded = attachmentMenu && !busy, onDismissRequest = { attachmentMenu = false },
                containerColor = colors.floatingSurface) {
                Text(attached?.title.orEmpty(), Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.reading_queue_file_private),
                    Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_replace_file)) },
                    leadingIcon = { Icon(Icons.Rounded.AttachFile, null) },
                    onClick = { attachmentMenu = false; pickFile() })
                if (attached != editor.originalAttachment) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.reading_queue_remove_file)) },
                        leadingIcon = { Icon(Icons.Rounded.Close, null) },
                        onClick = { attachmentMenu = false; actions.removeDraftFile() })
                }
            }
        }
    }
    val saveButton: @Composable (Modifier) -> Unit = { modifier ->
        Button(onClick = { focus.clearFocus(); actions.save() }, enabled = editor.valid && !busy,
            shape = RoundedCornerShape(18.dp), modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(if (editor.id == null) R.string.reading_queue_add else R.string.edit_save))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp)) {
        // Larger text can wrap without squeezing the labels into two narrow columns.
        if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.4f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    attachButton(Modifier.fillMaxWidth().heightIn(min = 52.dp))
                }
                saveButton(Modifier.fillMaxWidth().heightIn(min = 52.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                attachButton(Modifier.weight(1f).heightIn(min = 52.dp).fillMaxHeight())
                saveButton(Modifier.weight(1f).heightIn(min = 52.dp).fillMaxHeight())
            }
        }
    }
}
