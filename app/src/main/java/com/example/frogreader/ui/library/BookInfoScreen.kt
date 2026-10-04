package com.example.frogreader.ui.library

import com.example.frogreader.ui.theme.floatingSurface
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.example.frogreader.R
import com.example.frogreader.data.BookSharing
import com.example.frogreader.data.bookTransferFormat
import com.example.frogreader.data.bookTransferName
import com.example.frogreader.ui.profile.ReadingLogField
import com.example.frogreader.ui.profile.ReadingLogRatingEditor
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoScreen(bookId: String, onBack: () -> Unit, onEdit: () -> Unit,
    readingLogId: String? = null, reviewRequested: Boolean = false,
    onEditReadingLog: (String) -> Unit = {}, onOpenReadingLog: (String) -> Unit = {},
) {
    val model: BookInfoViewModel = viewModel(factory = BookInfoViewModel.factory(bookId, readingLogId))
    val state by model.state.collectAsStateWithLifecycle()
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
    val editReview = {
        val book = state.book
        val entryId = state.readingLogEntry?.id ?: book?.activeReadingLogId?.takeIf { !book.suppressReadingLog }
        if (entryId != null) onEditReadingLog(entryId)
        else if (book != null) {
            draftRating = book.ratingHalfStars ?: book.rating?.times(2)
            draftReview = book.review.orEmpty()
            originalRating = draftRating
            originalReview = draftReview
            reviewSheet = true
        }
    }
    LaunchedEffect(reviewRequested, state.loading) {
        if (reviewRequested && !reviewHandled && !state.loading && state.book != null) { reviewHandled = true; editReview() }
    }
    LaunchedEffect(state.reviewSavedGeneration) { if (state.reviewSavedGeneration > 0) reviewSheet = false }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val surface = MaterialTheme.colorScheme.surface
    val isLight = surface.luminance() > 0.5f
    LaunchedEffect(isLight) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }
    val chooserTitle = stringResource(R.string.book_info_share_chooser)
    LaunchedEffect(state.preparedShare) {
        val shared = state.preparedShare ?: return@LaunchedEffect
        try {
            context.startActivity(Intent.createChooser(BookSharing.intent(context, shared), chooserTitle))
            model.shareFinished()
        } catch (error: Exception) {
            model.shareFinished(error.message ?: "No app could open the share menu.")
        }
    }
    BookInfoContent(state, onBack, onEdit, model::share, editReview, onOpenReadingLog)
    if (reviewSheet) ModalBottomSheet(onDismissRequest = closeReview, sheetState = reviewSheetState,
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        sheetGesturesEnabled = !state.reviewSaving, modifier = Modifier.imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reading_log_review_action), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                FilledTonalIconButton(onClick = closeReview, enabled = !state.reviewSaving) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.reading_queue_close))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingLogRatingEditor(draftRating, { draftRating = it }, !state.reviewSaving)
                ReadingLogField(draftReview, { draftReview = it.take(20000) }, stringResource(R.string.reading_log_review), state.reviewSaving,
                    singleLine = false, minLines = 3, maxLines = 6)
                if (state.reviewError) Text(stringResource(R.string.reading_log_save_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { model.saveReview(draftRating, draftReview) }, enabled = !state.reviewSaving,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), shape = RoundedCornerShape(18.dp)) {
                if (state.reviewSaving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(stringResource(R.string.edit_save))
            }
        }
    }
    if (discardReview) AlertDialog(onDismissRequest = { discardReview = false }, containerColor = MaterialTheme.colorScheme.floatingSurface,
        title = { Text(stringResource(R.string.reading_queue_discard_title)) }, text = { Text(stringResource(R.string.reading_queue_discard_hint)) },
        confirmButton = { TextButton(onClick = { discardReview = false; reviewSheet = false }) { Text(stringResource(R.string.reading_queue_discard)) } },
        dismissButton = { TextButton(onClick = { discardReview = false }) { Text(stringResource(R.string.reading_queue_keep_editing)) } })
    if (state.shareError != null) {
        var details by remember(state.shareError) { mutableStateOf(false) }
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.floatingSurface,
            tonalElevation = 0.dp,
            onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.book_info_share_error)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.book_info_share_error_hint))
                    TextButton(onClick = { details = !details }) { Text(stringResource(R.string.metadata_error_details)) }
                    if (details) SelectionContainer {
                        Text(state.shareError.orEmpty(), Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { model.dismissError(); model.share() }) { Text(stringResource(R.string.metadata_retry)) } },
            dismissButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.metadata_close)) } },
        )
    }
}

@Composable
internal fun BookInfoContent(state: BookInfoState, onBack: () -> Unit, onEdit: () -> Unit, onShare: () -> Unit,
    onReview: () -> Unit = {}, onOpenReadingLog: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var showShareProgress by remember { mutableStateOf(false) }
    LaunchedEffect(state.sharing) {
        if (state.sharing) {
            delay(400)
            showShareProgress = true
        } else {
            showShareProgress = false
        }
    }
    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back)) }
            Text(stringResource(R.string.book_info_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(
                onClick = { if (!state.sharing) { if (state.readingLogEntry != null) onReview() else onEdit() } },
                enabled = (state.readingLogEntry != null || state.fileAvailable && state.book?.format?.supportsMetadataEditing == true) && !showShareProgress,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.library_menu_edit), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(2.dp))
            TextButton(
                onClick = { if (!state.sharing) onShare() },
                enabled = state.fileAvailable && !showShareProgress,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                if (showShareProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.book_info_share), style = MaterialTheme.typography.labelLarge)
            }
        }
        val book = state.book
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            book == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.book_info_missing), style = MaterialTheme.typography.bodyLarge)
            }
            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    BookIdentityHeader(book.title, book.author, state.cover, series = book.series)
                    if (state.readingLogEntry != null) ReadingLogDetails(state.readingLogEntry, onReview)
                    else BookReadingReview(book.ratingHalfStars ?: book.rating?.times(2), book.review, onReview)
                    val history = state.readingHistory.filter { it.id != state.readingLogEntry?.id }
                    if (history.isNotEmpty()) ReadingLogHistory(history, onOpenReadingLog)
                    if (!state.logOnly && !state.fileLoading && !state.fileAvailable) Text(stringResource(R.string.book_info_missing_file), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!state.logOnly) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            book.readingQueueNote?.takeIf { it.isNotBlank() }?.let { note ->
                                InfoSection(stringResource(R.string.reading_queue_saved_note)) {
                                    Text(note, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            InfoSection(stringResource(R.string.book_info_publication)) {
                                val unspecified = stringResource(R.string.book_info_not_specified)
                                InfoValue(stringResource(R.string.details_publisher), book.publisher?.takeIf { it.isNotBlank() } ?: unspecified)
                                InfoValue(stringResource(R.string.details_year), book.year?.takeIf { it.isNotBlank() } ?: unspecified)
                                book.series?.takeIf { it.isNotBlank() }?.let { series ->
                                    InfoValue(stringResource(R.string.details_series), book.seriesNumber?.let { "$series · #${formatSeriesNumber(it)}" } ?: series)
                                }
                                book.language?.takeIf { it.isNotBlank() }?.let { InfoValue(stringResource(R.string.details_language), displayLanguage(it)) }
                                if (book.genres.isNotEmpty()) InfoValue(stringResource(R.string.details_genres), book.genres.joinToString(", "))
                                if (book.translators.isNotEmpty()) InfoValue(stringResource(R.string.details_translators), book.translators.joinToString(", "))
                                book.isbn?.takeIf { it.isNotBlank() }?.let { InfoValue(stringResource(R.string.details_isbn), it) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SectionLabel(stringResource(R.string.metadata_description), Modifier.padding(start = 4.dp))
                                Text(
                                    book.description?.takeIf { it.isNotBlank() } ?: stringResource(R.string.book_info_no_description),
                                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyLarge,
                                    color = if (book.description.isNullOrBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                                )
                            }
                            InfoSection(stringResource(R.string.book_info_technical)) {
                                InfoValue(stringResource(R.string.details_format), state.fileInfo?.label ?: book.format.name)
                                if (state.sizeBytes > 0) InfoValue(stringResource(R.string.book_info_size), formatFileSize(state.sizeBytes))
                                if (state.fileAvailable) InfoValue(stringResource(R.string.book_info_filename), bookTransferName(book.title, bookTransferFormat(book.format, state.fileInfo?.label)))
                                state.fileInfo?.charset?.let { InfoValue(stringResource(R.string.book_info_encoding), it) }
                                InfoValue(stringResource(R.string.details_added), DateFormat.getDateInstance().format(Date(book.addedAtMillis)))
                                if (book.readingSeconds > 0) {
                                    val hours = book.readingSeconds / 3600
                                    val minutes = book.readingSeconds % 3600 / 60
                                    InfoValue(stringResource(R.string.details_time_read), if (hours > 0) stringResource(R.string.details_time_hm, hours, minutes) else stringResource(R.string.details_time_m, minutes))
                                }
                            }
                        }
                    }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun InfoValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.36f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.64f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSeriesNumber(number: Float): String =
    if (number % 1f == 0f) number.toInt().toString() else number.toString()

private fun displayLanguage(tag: String): String {
    val display = runCatching { Locale.forLanguageTag(tag).displayLanguage }.getOrDefault("")
    return if (display.isEmpty() || display == tag) tag else display.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
