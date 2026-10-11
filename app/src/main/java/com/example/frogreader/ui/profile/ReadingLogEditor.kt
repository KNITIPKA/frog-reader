package com.example.frogreader.ui.profile

import com.example.frogreader.ui.components.FrogSnackbarHost
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.ReadingLogDate
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.ui.library.BookIdentityHeader
import com.example.frogreader.ui.library.BookReadingReview
import com.example.frogreader.ui.theme.floatingSurface

@Composable
internal fun ReadingLogEditorForm(
    editor: ReadingLogEditor, busy: Boolean, cover: Any?, actions: ReadingLogActions, close: () -> Unit,
    snackbar: SnackbarHostState? = null,
) {
    val draft = editor.draft
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var dateTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var titleTouched by rememberSaveable(draft.id) { mutableStateOf(false) }
    val canEditIdentity = editor.canEditIdentity
    val endLabel = stringResource(when (draft.status) {
        ReadingLogStatus.FINISHED -> R.string.reading_log_completed
        ReadingLogStatus.ABANDONED -> R.string.reading_log_stopped
        ReadingLogStatus.IN_PROGRESS -> R.string.reading_log_completed
    })
    Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back))
                }
                Text(stringResource(if (editor.isNew) R.string.reading_log_add else R.string.reading_log_edit), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (canEditIdentity) ReadingLogBookFields(draft.title, draft.author.orEmpty(), cover, enabled = !busy,
                        titleIsError = draft.title.isBlank() && (!editor.isNew || titleTouched),
                        onTitleChange = { titleTouched = true; actions.change(draft.copy(title = it.replace('\n', ' ').replace('\r', ' ').take(200))) },
                        onAuthorChange = { actions.change(draft.copy(author = it.replace('\n', ' ').replace('\r', ' ').take(160))) },
                        onGallery = { focus.clearFocus(); keyboard?.hide(); actions.gallery() },
                        onCamera = { focus.clearFocus(); keyboard?.hide(); actions.camera() },
                        onRemoveCover = if (cover != null) { { focus.clearFocus(); keyboard?.hide(); actions.removeCover() } } else null,
                    ) else BookIdentityHeader(draft.title, draft.author, cover, compact = true, enabled = !busy)
                    ReadingLogSection {
                        ReadingLogFieldPair(first = { modifier ->
                            if (!canEditIdentity) ReadingLogDetailControl(stringResource(R.string.reading_log_source),
                                stringResource(R.string.reading_log_frogreader), modifier)
                            else Box(modifier) {
                                ReadingLogDetailControl(stringResource(R.string.reading_log_source),
                                    stringResource(sourceLabel(draft.source)),
                                    enabled = !busy, icon = Icons.Rounded.ExpandMore, click = { focus.clearFocus(); sourceMenu = true })
                                DropdownMenu(sourceMenu, { sourceMenu = false }, containerColor = colors.floatingSurface, tonalElevation = 0.dp) {
                                    listOf(ReadingLogSource.PAPER, ReadingLogSource.EXTERNAL).forEach { source ->
                                        DropdownMenuItem(text = { Text(stringResource(sourceLabel(source))) },
                                            trailingIcon = { if (draft.source == source) Icon(Icons.Rounded.Check, null) },
                                            onClick = { sourceMenu = false; actions.change(draft.copy(source = source)) })
                                    }
                                }
                            }
                        }, second = { modifier ->
                            Box(modifier) {
                                ReadingLogDetailControl(stringResource(R.string.reading_log_status_short), stringResource(statusLabel(draft.status)),
                                    enabled = !busy, icon = Icons.Rounded.ExpandMore, click = { focus.clearFocus(); statusMenu = true })
                                DropdownMenu(statusMenu, { statusMenu = false }, containerColor = colors.floatingSurface, tonalElevation = 0.dp) {
                                    ReadingLogStatus.entries.forEach { status -> DropdownMenuItem(text = { Text(stringResource(statusLabel(status))) },
                                        trailingIcon = { if (draft.status == status) Icon(Icons.Rounded.Check, null) }, onClick = {
                                            statusMenu = false
                                            actions.change(draft.withEditedStatus(status))
                                        })
                                    }
                                }
                            }
                        })
                        ReadingLogFieldPair(first = { modifier ->
                            ReadingLogDetailControl(stringResource(R.string.reading_log_started),
                                formatReadingLogDate(draft.startedOn) ?: stringResource(R.string.reading_log_date_not_set),
                                modifier, !busy, Icons.Rounded.CalendarToday) {
                                focus.clearFocus(); dateTarget = "start"
                            }
                        }, second = { modifier ->
                            ReadingLogDetailControl(endLabel,
                                formatReadingLogDate(draft.finishedOn) ?: stringResource(if (draft.status == ReadingLogStatus.IN_PROGRESS)
                                    R.string.reading_log_not_finished else R.string.reading_log_date_not_set), modifier,
                                enabled = !busy, icon = Icons.Rounded.CalendarToday, click = { focus.clearFocus(); dateTarget = "end" })
                        })
                        if (!datesInOrder(draft.startedOn, draft.finishedOn)) Text(stringResource(R.string.reading_log_date_order),
                            color = colors.error, style = MaterialTheme.typography.bodySmall)
                    }
                    BookReadingReview(draft.ratingHalfStars, draft.review, editing = true, enabled = !busy, compact = true,
                        onChange = { rating, review -> actions.change(draft.copy(ratingHalfStars = rating, review = review)) })
                    if (editor.failed) Text(stringResource(R.string.reading_log_save_failed), style = MaterialTheme.typography.bodySmall,
                        color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            snackbar?.let { FrogSnackbarHost(it) }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); actions.save() },
                    enabled = editor.valid && !busy && (editor.isNew || editor.dirty),
                    modifier = Modifier.widthIn(max = 648.dp).fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
                    if (busy) { CircularProgressIndicator(Modifier.size(18.dp), color = colors.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(stringResource(R.string.edit_save))
                }
            }
        }
    }
    dateTarget?.let { target ->
        ReadingLogDateDialog(if (target == "start") draft.startedOn else draft.finishedOn,
            if (target == "start") stringResource(R.string.reading_log_started) else endLabel, { dateTarget = null }) { date ->
            actions.change(if (target == "start") draft.copy(startedOn = date) else draft.withEditedEndDate(date)); dateTarget = null
        }
    }
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
