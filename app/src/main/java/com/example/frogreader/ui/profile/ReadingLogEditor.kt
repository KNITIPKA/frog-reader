package com.example.frogreader.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.library.BookIdentityHeader
import com.example.frogreader.ui.theme.floatingSurface
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingLogEditorForm(editor: ReadingLogEditor, busy: Boolean, cover: File?, actions: ReadingLogActions, close: () -> Unit,
    autoFocusTitle: Boolean = false,
) {
    val draft = editor.draft
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val titleFocus = remember { FocusRequester() }
    var dateTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var coverMenu by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocusTitle) { if (autoFocusTitle) { titleFocus.requestFocus(); keyboard?.show() } }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (editor.isNew) R.string.reading_log_add else R.string.reading_log_edit), Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            FilledTonalIconButton(onClick = close, enabled = !busy) { Icon(Icons.Rounded.Close, stringResource(R.string.reading_queue_close)) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.reading_log_editor_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            ReadingLogField(draft.title, { actions.change(draft.copy(title = it.take(200))) }, stringResource(R.string.reading_queue_title), busy,
                Modifier.focusRequester(titleFocus), keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            ReadingLogField(draft.author, { actions.change(draft.copy(author = it.take(160))) }, stringResource(R.string.reading_queue_author), busy,
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
            ReadingLogField(draft.series, { actions.change(draft.copy(series = it.take(200))) }, stringResource(R.string.reading_log_series), busy,
                keyboardActions = KeyboardActions(onNext = { focus.clearFocus() }))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    InputChip(selected = false, onClick = { sourceMenu = true }, enabled = !busy && draft.source != ReadingLogSource.FROGREADER,
                        label = { Text(stringResource(sourceLabel(draft.source))) }, trailingIcon = { if (draft.source != ReadingLogSource.FROGREADER) Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
                    DropdownMenu(sourceMenu, { sourceMenu = false }, containerColor = colors.floatingSurface) {
                        listOf(ReadingLogSource.PAPER, ReadingLogSource.EXTERNAL).forEach { source ->
                            DropdownMenuItem(text = { Text(stringResource(sourceLabel(source))) }, onClick = { sourceMenu = false; actions.change(draft.copy(source = source)) })
                        }
                    }
                }
                Box {
                    InputChip(selected = true, onClick = { statusMenu = true }, enabled = !busy,
                        label = { Text(stringResource(statusLabel(draft.status))) }, trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) })
                    DropdownMenu(statusMenu, { statusMenu = false }, containerColor = colors.floatingSurface) {
                        ReadingLogStatus.entries.forEach { status -> DropdownMenuItem(text = { Text(stringResource(statusLabel(status))) },
                            onClick = {
                                statusMenu = false
                                val end = draft.finishedOn ?: if (draft.status == ReadingLogStatus.IN_PROGRESS && status != ReadingLogStatus.IN_PROGRESS)
                                    draft.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) } else null
                                actions.change(draft.copy(status = status, finishedOn = end))
                            }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateButton(stringResource(R.string.reading_log_started), draft.startedOn, !busy, Modifier.weight(1f)) { focus.clearFocus(); dateTarget = "start" }
                val endLabel = stringResource(when (draft.status) {
                    ReadingLogStatus.FINISHED -> R.string.reading_log_completed
                    ReadingLogStatus.ABANDONED -> R.string.reading_log_stopped
                    ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_last_opened
                })
                val displayedEnd = if (draft.status == ReadingLogStatus.IN_PROGRESS && draft.source == ReadingLogSource.FROGREADER)
                    draft.lastOpenedAtMillis?.let { ReadingLogDate.fromMillis(it) } ?: draft.finishedOn else draft.finishedOn
                DateButton(endLabel, displayedEnd, !busy && !(draft.status == ReadingLogStatus.IN_PROGRESS && draft.source == ReadingLogSource.FROGREADER), Modifier.weight(1f)) {
                    focus.clearFocus(); dateTarget = "end"
                }
            }
            if (!datesInOrder(draft.startedOn, draft.finishedOn)) Text(stringResource(R.string.reading_log_date_order), color = colors.error, style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reading_log_cover), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (cover != null) BookIdentityHeader(draft.title, draft.author, cover, enabled = !busy, onChangeCover = { coverMenu = true }, onRemoveCover = actions.removeCover)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (draft.source == ReadingLogSource.PAPER) OutlinedButton(onClick = { focus.clearFocus(); actions.camera() }, enabled = !busy) {
                        Icon(Icons.Rounded.CameraAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reading_log_camera))
                    }
                    OutlinedButton(onClick = { focus.clearFocus(); actions.gallery() }, enabled = !busy) {
                        Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reading_log_gallery))
                    }
                }
                Text(stringResource(R.string.reading_log_cover_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            ReadingLogRatingEditor(draft.ratingHalfStars, { actions.change(draft.copy(ratingHalfStars = it)) }, !busy)
            ReadingLogField(draft.review, { actions.change(draft.copy(review = it.take(20000))) }, stringResource(R.string.reading_log_review), busy,
                singleLine = false, minLines = 3, maxLines = 6,
                supportingText = { Text(stringResource(R.string.reading_log_review_hint)) })
            if (editor.failed) Text(stringResource(R.string.reading_log_save_failed), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Button(onClick = { focus.clearFocus(); actions.save() }, enabled = editor.valid && !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(14.dp)) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(R.string.edit_save))
        }
    }
    dateTarget?.let { target -> ReadingLogDateDialog(if (target == "start") draft.startedOn else draft.finishedOn,
        stringResource(if (target == "start") R.string.reading_log_started else R.string.reading_log_completed), { dateTarget = null }) { date ->
        actions.change(if (target == "start") draft.copy(startedOn = date) else draft.copy(finishedOn = date)); dateTarget = null
    } }
    if (coverMenu) AlertDialog(onDismissRequest = { coverMenu = false }, containerColor = colors.floatingSurface,
        title = { Text(stringResource(R.string.reading_log_cover)) }, text = {
            Column {
                if (draft.source == ReadingLogSource.PAPER) TextButton(onClick = { coverMenu = false; focus.clearFocus(); actions.camera() }) { Text(stringResource(R.string.reading_log_camera)) }
                TextButton(onClick = { coverMenu = false; focus.clearFocus(); actions.gallery() }) { Text(stringResource(R.string.reading_log_gallery)) }
                TextButton(onClick = { coverMenu = false; actions.removeCover() }) { Text(stringResource(R.string.reading_log_remove_cover), color = colors.error) }
            }
        }, confirmButton = { TextButton(onClick = { coverMenu = false }) { Text(stringResource(R.string.metadata_close)) } })
}

@Composable
internal fun ReadingLogField(value: String, change: (String) -> Unit, label: String, busy: Boolean, modifier: Modifier = Modifier,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = 1, supportingText: @Composable (() -> Unit)? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    TextField(value, change, modifier.fillMaxWidth(), enabled = !busy, label = { Text(label) }, singleLine = singleLine,
        minLines = minLines, maxLines = maxLines, supportingText = supportingText, shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        keyboardActions = keyboardActions, colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent))
}

@Composable
internal fun ReadingLogRatingEditor(rating: Int?, change: (Int?) -> Unit, enabled: Boolean = true) {
    val ratingLabel = stringResource(R.string.reading_log_rating)
    val ratingState = if (rating == null) stringResource(R.string.reading_log_unrated) else stringResource(R.string.reading_log_rating_value, ratingText(rating))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reading_log_rating), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { change(null) }, enabled = enabled && rating != null) { Text(stringResource(R.string.reading_log_clear_rating)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            FilledTonalIconButton(onClick = { change(((rating ?: 3) - 1).coerceAtLeast(2)) }, enabled = enabled && (rating == null || rating > 2)) {
                Icon(Icons.Rounded.Remove, stringResource(R.string.reading_log_rating_less))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (rating == null) Text(stringResource(R.string.reading_log_unrated), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else ReadingLogStars(rating, showValue = true)
            }
            FilledTonalIconButton(onClick = { change(((rating ?: 1) + 1).coerceAtMost(10)) }, enabled = enabled && (rating == null || rating < 10)) {
                Icon(Icons.Rounded.Add, stringResource(R.string.reading_log_rating_more))
            }
        }
        Slider(value = (rating ?: 2).toFloat(), onValueChange = { change(it.roundToInt().coerceIn(2, 10)) }, enabled = enabled,
            valueRange = 2f..10f, steps = 7, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = ratingLabel; stateDescription = ratingState
            })
    }
}

@Composable
private fun DateButton(label: String, date: ReadingLogDate?, enabled: Boolean, modifier: Modifier, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatReadingLogDate(date) ?: stringResource(R.string.reading_log_unknown_date), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingLogDateDialog(value: ReadingLogDate?, title: String, dismiss: () -> Unit, save: (ReadingLogDate?) -> Unit) {
    val today = remember { LocalDate.now() }
    var precision by rememberSaveable { mutableIntStateOf(when { value == null -> 3; value.month == null -> 2; value.day == null -> 1; else -> 0 }) }
    var year by rememberSaveable { mutableStateOf((value?.year ?: today.year).toString()) }
    var month by rememberSaveable { mutableStateOf((value?.month ?: today.monthValue).toString()) }
    var day by rememberSaveable { mutableStateOf((value?.day ?: today.dayOfMonth).toString()) }
    val date = if (precision == 3) null else year.toIntOrNull()?.let { y ->
        ReadingLogDate(y, if (precision <= 1) month.toIntOrNull() ?: 0 else null, if (precision == 0) day.toIntOrNull() ?: 0 else null)
    }
    val valid = precision == 3 || date?.isValid() == true
    AlertDialog(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.floatingSurface, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(R.string.reading_log_date_exact, R.string.reading_log_date_month, R.string.reading_log_date_year, R.string.reading_log_unknown_date).forEachIndexed { index, label ->
                    FilterChip(precision == index, { precision = index }, label = { Text(stringResource(label)) })
                }
            }
            if (precision != 3) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.weight(1.35f), label = { Text(stringResource(R.string.reading_log_year)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
                if (precision <= 1) OutlinedTextField(month, { month = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text(stringResource(R.string.reading_log_month)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
                if (precision == 0) OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text(stringResource(R.string.reading_log_day)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
            }
            if (!valid) Text(stringResource(R.string.reading_log_date_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { save(date) }, enabled = valid) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.library_delete_cancel)) } })
}
