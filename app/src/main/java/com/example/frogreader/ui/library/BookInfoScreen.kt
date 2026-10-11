package com.example.frogreader.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.BookSharing
import com.example.frogreader.data.ReadingLogDeletion
import com.example.frogreader.data.bookTransferFormat
import com.example.frogreader.data.bookTransferName
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.EditableBookMetadata
import com.example.frogreader.ui.profile.ReadingLogRatingEditor
import com.example.frogreader.ui.theme.floatingSurface
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoScreen(
    bookId: String,
    onBack: () -> Unit,
    readingLogId: String? = null,
    reviewRequested: Boolean = false,
    editRequested: Boolean = false,
    onEditReadingLog: (String) -> Unit = {},
    onOpenReadingLog: (String) -> Unit = {},
    onReadingLogDeleted: ((ReadingLogDeletion) -> Unit)? = null,
) {
    val model: BookInfoViewModel = viewModel(factory = BookInfoViewModel.factory(bookId, readingLogId))
    val state by model.state.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(editRequested && readingLogId == null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var removingReading by rememberSaveable { mutableStateOf(false) }
    val readingDeletedCallback by rememberUpdatedState(onReadingLogDeleted ?: { _: ReadingLogDeletion -> onBack() })
    LaunchedEffect(state.readingRemoved) {
        if (state.readingRemoved) {
            val receipt = state.removedReading
            model.readingRemovalHandled()
            if (receipt != null) readingDeletedCallback(receipt) else onBack()
        }
    }
    val metadata: BookMetadataViewModel? = if (editing) viewModel(key = "metadata:$bookId",
        factory = BookMetadataViewModel.factory(bookId)) else null
    val editor = metadata?.state?.collectAsStateWithLifecycle()?.value
    val savedAtEntry = remember(metadata) { metadata?.state?.value?.savedGeneration ?: 0 }
    LaunchedEffect(metadata) { metadata?.startEditing() }
    LaunchedEffect(editor?.savedGeneration) {
        if (editor != null && editor.savedGeneration > savedAtEntry) editing = false
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cancelEdit = { focus.clearFocus(); keyboard?.hide(); metadata?.discardChanges(); editing = false }
    val closeEdit = { if (editor?.busy != true) { if (editor?.dirty == true) discard = true else cancelEdit() } }
    BackHandler(editing, onBack = closeEdit)
    BackHandler(state.removingReading) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) metadata?.pickCover(uri) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) metadata?.export(uri) }
    val actions = BookDetailsEditActions(
        change = { metadata?.change(it) }, changeReview = { rating, review -> metadata?.changeReview(rating, review) },
        save = { focus.clearFocus(); keyboard?.hide(); metadata?.save() }, cancel = closeEdit,
        pickCover = { focus.clearFocus(); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        removeCover = { metadata?.removeCover() }, resetCover = { metadata?.resetCover() },
        export = { metadata?.let { export.launch(it.exportName()) } }, retry = { metadata?.load() },
    )
    var reviewHandled by rememberSaveable { mutableStateOf(false) }
    var reviewSheet by rememberSaveable { mutableStateOf(false) }
    var draftRating by rememberSaveable { mutableStateOf<Int?>(null) }
    var draftReview by rememberSaveable { mutableStateOf("") }
    var originalRating by rememberSaveable { mutableStateOf<Int?>(null) }
    var originalReview by rememberSaveable { mutableStateOf("") }
    var discardReview by rememberSaveable { mutableStateOf(false) }
    val reviewDirty = draftRating != originalRating || draftReview != originalReview
    val currentReviewBusy by rememberUpdatedState(state.reviewSaving)
    val currentReviewDirty by rememberUpdatedState(reviewDirty)
    val closeReview = { if (!state.reviewSaving) { if (reviewDirty) discardReview = true else reviewSheet = false } }
    val reviewSheetState = key(reviewSheet) { rememberBottomSheetState(initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded), confirmValueChange = { target ->
            if (target != SheetValue.Hidden) true
            else if (currentReviewBusy) false
            else if (currentReviewDirty) { discardReview = true; false } else true
        }) }
    val canEditBook = state.readingLogEntry == null && state.fileAvailable && state.book?.format?.supportsMetadataEditing == true
    val editReview = {
        val book = state.book
        val reading = state.readingLogEntry
        if (reading != null) onEditReadingLog(reading.id)
        else if (canEditBook) editing = true
        else {
            val entryId = book?.activeReadingLogId?.takeIf { !book.suppressReadingLog }
            if (entryId != null) onEditReadingLog(entryId)
            else if (book != null) {
                draftRating = book.ratingHalfStars ?: book.rating?.times(2)
                draftReview = book.review.orEmpty()
                originalRating = draftRating; originalReview = draftReview
                reviewSheet = true
            }
        }
    }
    LaunchedEffect(reviewRequested, state.loading) {
        if (reviewRequested && !reviewHandled && !state.loading && state.book != null) { reviewHandled = true; editReview() }
    }
    LaunchedEffect(state.reviewSavedGeneration) { if (state.reviewSavedGeneration > 0) reviewSheet = false }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    LaunchedEffect(isLight) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight; isAppearanceLightNavigationBars = isLight
        }
    }
    val chooserTitle = stringResource(R.string.book_info_share_chooser)
    LaunchedEffect(state.preparedShare) {
        val shared = state.preparedShare ?: return@LaunchedEffect
        try {
            context.startActivity(Intent.createChooser(BookSharing.intent(context, shared), chooserTitle))
            model.shareFinished()
        } catch (error: Exception) { model.shareFinished(error.message ?: "No app could open the share menu.") }
    }
    BookInfoContent(state, onBack = { if (!state.removingReading) { if (editing) closeEdit() else onBack() } },
        onEdit = { if (state.readingLogEntry != null) editReview() else editing = true }, onShare = model::share,
        onReview = editReview, onOpenReadingLog = onOpenReadingLog, onRemoveReading = { removingReading = true },
        editor = editor, editActions = actions)
    if (removingReading) AlertDialog(onDismissRequest = { if (!state.removingReading) removingReading = false },
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        title = { Text(stringResource(R.string.reading_log_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reading_log_delete_hint))
                if (state.readingRemovalError) Text(stringResource(R.string.reading_log_action_failed),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        },
        confirmButton = { TextButton(onClick = model::removeReading, enabled = !state.removingReading) {
            if (state.removingReading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (state.readingRemovalError) R.string.metadata_retry else R.string.reading_log_remove_action),
                color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { removingReading = false }, enabled = !state.removingReading) {
            Text(stringResource(R.string.library_delete_cancel))
        } })
    if (reviewSheet) ModalBottomSheet(onDismissRequest = closeReview, sheetState = reviewSheetState,
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        sheetGesturesEnabled = !state.reviewSaving, modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reading_log_review_action), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = closeReview, enabled = !state.reviewSaving) { Icon(Icons.Rounded.Close, stringResource(R.string.reading_queue_close)) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ReadingLogRatingEditor(draftRating, { draftRating = it }, !state.reviewSaving)
                BookDetailField(draftReview, label = stringResource(R.string.reading_log_review),
                    placeholder = stringResource(R.string.reading_log_review_hint), onChange = { draftReview = it.take(20000) },
                    enabled = !state.reviewSaving, minLines = 3)
                if (state.reviewError) Text(stringResource(R.string.reading_log_save_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.saveReview(draftRating, draftReview) },
                enabled = !state.reviewSaving, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.edit_save)) }
        }
    }
    if (discard || discardReview) AlertDialog(onDismissRequest = { discard = false; discardReview = false },
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        title = { Text(stringResource(R.string.metadata_discard_title)) }, text = { Text(stringResource(R.string.metadata_discard_hint)) },
        confirmButton = { TextButton(onClick = {
            if (discard) { discard = false; cancelEdit() } else { discardReview = false; reviewSheet = false }
        }) { Text(stringResource(R.string.metadata_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false; discardReview = false }) { Text(stringResource(R.string.metadata_keep_editing)) } })
    if (state.shareError != null) {
        var details by remember(state.shareError) { mutableStateOf(false) }
        AlertDialog(containerColor = MaterialTheme.colorScheme.floatingSurface, tonalElevation = 0.dp,
            onDismissRequest = model::dismissError, title = { Text(stringResource(R.string.book_info_share_error)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.book_info_share_error_hint))
                TextButton(onClick = { details = !details }) { Text(stringResource(R.string.metadata_error_details)) }
                if (details) SelectionContainer { Text(state.shareError.orEmpty(),
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
            } }, confirmButton = { TextButton(onClick = { model.dismissError(); model.share() }) { Text(stringResource(R.string.metadata_retry)) } },
            dismissButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.metadata_close)) } })
    }
}

@Composable
internal fun BookInfoContent(
    state: BookInfoState, onBack: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit,
    onReview: () -> Unit = {}, onOpenReadingLog: (String) -> Unit = {}, onRemoveReading: () -> Unit = {},
    editor: MetadataEditorState? = null, editActions: BookDetailsEditActions = BookDetailsEditActions(),
    scrollState: ScrollState = rememberScrollState(),
) {
    val colors = MaterialTheme.colorScheme
    val editing = editor != null
    val editable = editing && !editor.loading && editor.draft != null
    val enabled = editor?.busy != true && editor?.loading != true && !state.removingReading
    var menu by remember { mutableStateOf(false) }
    var aboutSaving by rememberSaveable { mutableStateOf(false) }
    var errorDetails by rememberSaveable(editor?.error) { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = editor?.busy != true && !state.removingReading) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back))
                }
                Text(stringResource(if (state.readingLogEntry != null) R.string.reading_log_details else R.string.book_info_title),
                    Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (editing) TextButton(onClick = editActions.cancel, enabled = editor.busy != true) {
                    Text(stringResource(R.string.library_delete_cancel))
                } else FilledTonalButton(onClick = onEdit,
                    enabled = enabled && !state.sharing && (state.readingLogEntry != null || state.fileAvailable && state.book?.format?.supportsMetadataEditing == true),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.library_menu_edit))
                }
                if (state.fileAvailable || editing || state.readingLogEntry != null) Box {
                    IconButton(onClick = { menu = true }, enabled = enabled && !state.sharing) {
                        if (state.sharing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.MoreVert, stringResource(R.string.metadata_more_actions))
                    }
                    DropdownMenu(menu, { menu = false }, containerColor = colors.floatingSurface, tonalElevation = 0.dp) {
                        if (!editing) {
                            if (state.fileAvailable) DropdownMenuItem(text = { Text(stringResource(R.string.book_info_share)) },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { menu = false; onShare() })
                            if (state.readingLogEntry != null) DropdownMenuItem(
                                text = { Text(stringResource(R.string.reading_log_remove_action), color = colors.error) },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = colors.error) },
                                onClick = { menu = false; onRemoveReading() })
                        }
                        else {
                            DropdownMenuItem(text = { Text(stringResource(R.string.metadata_export)) },
                                leadingIcon = { Icon(Icons.Rounded.IosShare, null) }, enabled = editable && !editor.dirty,
                                onClick = { menu = false; editActions.export() })
                            if (editor.dirty) Text(stringResource(R.string.metadata_export_after_save),
                                Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            DropdownMenuItem(text = { Text(stringResource(R.string.metadata_about_saving)) },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { menu = false; aboutSaving = true })
                        }
                    }
                }
            }
            val book = state.book ?: editor?.book
            when {
                state.loading && book == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                book == null -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.book_info_missing), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState), horizontalAlignment = Alignment.CenterHorizontally) {
                    val draft = editor?.draft ?: EditableBookMetadata.from(book)
                    val titleOnly = (editor?.fileInfo ?: state.fileInfo)?.titleOnly == true
                    val change = editActions.change.takeIf { editable }
                    Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = if (state.readingLogEntry != null) 16.dp else 18.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(if (state.readingLogEntry != null) 10.dp else 16.dp)) {
                        if (editor?.error != null) {
                            MetadataErrorCard(editor.errorAction) { errorDetails = true }
                            if (editor.draft == null) OutlinedButton(onClick = editActions.retry) { Text(stringResource(R.string.metadata_retry)) }
                        }
                        BookIdentityHeader(draft.title, draft.authors.joinToString("\n"),
                            cover = if (editor?.removeCover == true) null else editor?.pickedCover?.let(Uri::parse) ?: state.cover ?: editor?.cover,
                            enabled = enabled,
                            onTitleChange = change?.let { { title -> it(draft.copy(title = title.replace('\n', ' ').replace('\r', ' '))) } },
                            onAuthorChange = change?.takeUnless { titleOnly }?.let { { authors -> it(draft.copy(authors = authors.split('\n'))) } },
                            onChangeCover = editActions.pickCover.takeIf { editable && !titleOnly },
                            onRemoveCover = editActions.removeCover.takeIf { editable && !titleOnly && !editor.removeCover && (editor.pickedCover != null || state.cover != null || editor.cover != null) },
                            onRestoreCover = editActions.resetCover.takeIf { editor?.pickedCover != null || editor?.removeCover == true },
                            compact = state.readingLogEntry != null,
                        )
                        if (!titleOnly && state.readingLogEntry == null) {
                            BookDetailSection(stringResource(R.string.metadata_description)) {
                                BookDetailField(draft.description, placeholder = stringResource(R.string.book_info_no_description),
                                    onChange = change?.let { { description -> it(draft.copy(description = description)) } }, enabled = enabled, minLines = 2)
                                BookDetailField(draft.genres.joinToString("\n"), label = stringResource(R.string.details_genres),
                                    placeholder = stringResource(R.string.book_info_not_specified),
                                    onChange = change?.let { { genres -> it(draft.copy(genres = genres.split('\n'))) } }, enabled = enabled,
                                    supportingText = stringResource(R.string.metadata_one_per_line))
                            }
                        }
                        if (state.readingLogEntry != null) ReadingLogDetails(state.readingLogEntry, onReview,
                            format = if (!state.logOnly) state.fileInfo?.label ?: book.format.name else state.readingLogEntry.bookFormat?.name)
                        else BookReadingReview(if (editor != null) editor.ratingHalfStars else book.ratingHalfStars ?: book.rating?.times(2),
                            editor?.review ?: book.review, onReview, editing = editable, enabled = enabled,
                            onChange = editActions.changeReview)
                        if (!titleOnly && state.readingLogEntry == null) BookPublicationSection(draft, change, enabled)
                        book.readingQueueNote?.takeIf { it.isNotBlank() && state.readingLogEntry == null }?.let { note ->
                            BookDetailSection(stringResource(R.string.reading_queue_saved_note)) { BookDetailField(note) }
                        }
                        val history = state.readingHistory.filter { it.id != state.readingLogEntry?.id }
                        if (history.isNotEmpty()) ReadingLogHistory(history, onOpenReadingLog)
                        if (!state.logOnly && !state.fileLoading && !state.fileAvailable) Text(stringResource(R.string.book_info_missing_file),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
                        if (state.readingLogEntry == null) BookFileDetails(state)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            if (editing) Surface(color = colors.surface, tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = editActions.save, enabled = editor.canSave,
                        modifier = Modifier.widthIn(max = 644.dp).fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                        if (editor.busy) { CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(if (editor.busy) R.string.metadata_saving else R.string.book_details_save_changes))
                    }
                    if (editor.exported) Text(stringResource(R.string.metadata_exported), Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = colors.primary)
                }
            }
        }
    }
    if (aboutSaving) AlertDialog(onDismissRequest = { aboutSaving = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.metadata_about_saving)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.metadata_file_hint))
                if (editor?.fileInfo?.titleOnly == true) Text(stringResource(R.string.metadata_palmdoc_hint))
                else {
                    editor?.fileInfo?.charset?.takeIf { it != "UTF-8" }?.let { Text(stringResource(R.string.metadata_charset_hint, it)) }
                    if (state.book?.format == BookFormat.MOBI) Text(stringResource(R.string.metadata_mobi_hint))
                }
            }
        }, confirmButton = { TextButton(onClick = { aboutSaving = false }) { Text(stringResource(R.string.metadata_close)) } })
    if (errorDetails && editor?.error != null) AlertDialog(onDismissRequest = { errorDetails = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.metadata_error_details)) },
        text = { SelectionContainer { Text(editor.error, Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { errorDetails = false }) { Text(stringResource(R.string.metadata_close)) } })
}

@Composable
internal fun BookPublicationSection(draft: EditableBookMetadata, change: ((EditableBookMetadata) -> Unit)? = null,
    enabled: Boolean = true, expandedInitially: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(expandedInitially) }
    val unspecified = stringResource(R.string.book_info_not_specified)
    BookDetailSection(stringResource(R.string.metadata_edition)) {
        BookDetailPair(first = { modifier ->
            BookDetailField(draft.series, modifier, label = stringResource(R.string.details_series), placeholder = unspecified,
                onChange = change?.let { { series -> it(draft.copy(series = series)) } }, enabled = enabled)
        }, second = { modifier ->
            BookDetailField(draft.seriesNumber, modifier, label = stringResource(R.string.metadata_series_number), placeholder = unspecified,
                onChange = change?.let { { number -> it(draft.copy(seriesNumber = number)) } }, enabled = enabled, maxLines = 1,
                isError = draft.seriesNumber.isNotBlank() && (draft.series.isBlank() || draft.seriesNumber.toFloatOrNull()?.let { it.isFinite() && it >= 0 } != true),
                supportingText = stringResource(R.string.metadata_series_hint), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next))
        })
        BookDetailPair(first = { modifier ->
            BookDetailField(draft.publisher, modifier, label = stringResource(R.string.details_publisher), placeholder = unspecified,
                onChange = change?.let { { publisher -> it(draft.copy(publisher = publisher)) } }, enabled = enabled)
        }, second = { modifier ->
            BookDetailField(draft.year, modifier, label = stringResource(R.string.details_year), placeholder = unspecified,
                onChange = change?.let { { year -> it(draft.copy(year = year)) } }, enabled = enabled, maxLines = 1,
                isError = draft.year.isNotBlank() && !draft.year.matches(Regex("[0-9]{4}")), supportingText = stringResource(R.string.metadata_year_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        })
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.book_details_more)); Spacer(Modifier.width(6.dp))
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
        if (expanded) {
            BookDetailPair(first = { modifier ->
                BookDetailField(draft.language, modifier, label = stringResource(R.string.details_language), placeholder = unspecified,
                    onChange = change?.let { { language -> it(draft.copy(language = language)) } }, enabled = enabled, maxLines = 1,
                    isError = draft.language.isNotBlank() && !draft.language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")),
                    supportingText = stringResource(R.string.metadata_language_hint), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            }, second = { modifier ->
                BookDetailField(draft.isbn, modifier, label = stringResource(R.string.details_isbn), placeholder = unspecified,
                    onChange = change?.let { { isbn -> it(draft.copy(isbn = isbn)) } }, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
            })
            BookDetailField(draft.translators.joinToString("\n"), label = stringResource(R.string.details_translators), placeholder = unspecified,
                onChange = change?.let { { translators -> it(draft.copy(translators = translators.split('\n'))) } }, enabled = enabled,
                supportingText = stringResource(R.string.metadata_one_per_line))
        }
    }
}

@Composable
private fun BookFileDetails(state: BookInfoState) {
    val book = state.book ?: return
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLowest, contentColor = colors.onSurface, tonalElevation = 0.dp) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { expanded = !expanded }).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Description, null, tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.book_info_technical), style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(state.fileInfo?.label ?: book.format.name, state.sizeBytes.takeIf { it > 0 }?.let(::formatFileSize)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = colors.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.fileAvailable) BookDetailField(bookTransferName(book.title, bookTransferFormat(book.format, state.fileInfo?.label)),
                    label = stringResource(R.string.book_info_filename))
                state.fileInfo?.charset?.let { BookDetailField(it, label = stringResource(R.string.book_info_encoding)) }
                BookDetailField(DateFormat.getDateInstance().format(Date(book.addedAtMillis)), label = stringResource(R.string.details_added))
                if (book.readingSeconds > 0) {
                    val hours = book.readingSeconds / 3600; val minutes = book.readingSeconds % 3600 / 60
                    BookDetailField(if (hours > 0) stringResource(R.string.details_time_hm, hours, minutes) else stringResource(R.string.details_time_m, minutes),
                        label = stringResource(R.string.details_time_read))
                }
            }
        }
    }
}
