@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.model.PdfTextBounds
import com.example.frogreader.data.model.Quote
import com.example.frogreader.ui.reader.ReaderColors
import java.util.UUID

/** A detached selection snapshot survives clearing native selection, rotation and process restore. */
internal data class PdfNoteDraft(
    val text: String,
    val bounds: List<PdfTextBounds>,
    val quoteId: String? = null,
    val note: String = "",
    val saveId: String = UUID.randomUUID().toString(),
) {
    companion object {
        val Saver = listSaver<PdfNoteDraft?, Any>(
            save = { draft ->
                if (draft == null) emptyList() else listOf(draft.text, draft.quoteId.orEmpty(), draft.note, draft.saveId) + draft.bounds.flatMap {
                    listOf(it.pageIndex, it.left, it.top, it.right, it.bottom)
                }
            },
            restore = { saved ->
                if (saved.isEmpty()) null else PdfNoteDraft(saved[0] as String, saved.drop(4).chunked(5).map {
                    PdfTextBounds(it[0] as Int, it[1] as Float, it[2] as Float, it[3] as Float, it[4] as Float)
                }, (saved[1] as String).ifEmpty { null }, saved[2] as String, saved[3] as String)
            },
        )
    }
}

@Stable
internal class PdfNotesState(
    val draft: MutableState<PdfNoteDraft?>,
    val viewedId: MutableState<String?>,
    val pendingId: MutableState<String?>,
    val pendingText: MutableState<String?>,
)

/** Kept outside document identity keys so a reopened renderer cannot discard a user's draft. */
@Composable
internal fun rememberPdfNotesState(bookId: String, writeSessionId: String): PdfNotesState {
    val draft = rememberSaveable(bookId, stateSaver = PdfNoteDraft.Saver) { mutableStateOf<PdfNoteDraft?>(null) }
    val viewed = rememberSaveable(bookId) { mutableStateOf<String?>(null) }
    val pending = rememberSaveable(bookId) { mutableStateOf<String?>(null) }
    val pendingText = rememberSaveable(bookId) { mutableStateOf<String?>(null) }
    var owner by rememberSaveable(bookId) { mutableStateOf(writeSessionId) }
    LaunchedEffect(writeSessionId) {
        if (owner != writeSessionId) {
            // A process restart cannot restore the previous VM's in-memory write queue.
            pending.value = null
            pendingText.value = null
            owner = writeSessionId
        }
    }
    return remember(draft, viewed, pending, pendingText) { PdfNotesState(draft, viewed, pending, pendingText) }
}

@Composable
internal fun PdfNoteEditor(
    draft: PdfNoteDraft,
    colors: ReaderColors,
    saving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onDraftChange: ((String) -> Unit)? = null,
) {
    var localNote by rememberSaveable(draft.saveId) { mutableStateOf(draft.note) }
    val note = if (onDraftChange != null) draft.note else localNote
    val focus = remember { FocusRequester() }
    val busy by rememberUpdatedState(saving)
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !busy }),
        containerColor = colors.chrome, contentColor = colors.onChrome,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !saving,
            shouldDismissOnClickOutside = !saving),
    ) {
        PdfNoteEditorContent(draft, colors, saving, note, onNoteChange = { value ->
            if (onDraftChange != null) onDraftChange(value) else localNote = value
        }, onSave = onSave, onDismiss = onDismiss, focusRequester = focus)
    }
    LaunchedEffect(draft.saveId) { focus.requestFocus() }
}

/** The sheet body is shared with native previews, which cannot capture separate dialog windows. */
@Composable
internal fun PdfNoteEditorContent(
    draft: PdfNoteDraft,
    colors: ReaderColors,
    saving: Boolean,
    note: String,
    onNoteChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (draft.quoteId == null) R.string.pdf_selection_add_note else R.string.pdf_note_edit),
            style = MaterialTheme.typography.titleLarge)
        Text(draft.text, color = colors.onChromeVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()))
        OutlinedTextField(
            value = note, onValueChange = { onNoteChange(it.take(20_000)) },
            modifier = Modifier.fillMaxWidth().then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            enabled = !saving, minLines = 3, maxLines = 8,
            label = { Text(stringResource(R.string.reader_note_title)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.onChrome, unfocusedTextColor = colors.onChrome,
                cursorColor = colors.accent, focusedBorderColor = colors.accent,
                focusedLabelColor = colors.accent, unfocusedLabelColor = colors.onChromeVariant,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.backup_cancel)) }
            Button(onClick = { onSave(note.trim()) }, enabled = !saving && note.isNotBlank()) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.pdf_note_save))
            }
        }
    }
}

/** Same modal sheet interaction as the reader's footnotes, with the selected passage as context. */
@Composable
internal fun PdfNoteViewer(quote: Quote, colors: ReaderColors, onEdit: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.chrome, contentColor = colors.onChrome) {
        PdfNoteViewerContent(quote, colors, onEdit)
    }
}

@Composable
internal fun PdfNoteViewerContent(quote: Quote, colors: ReaderColors, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
        .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.reader_note_title), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.EditNote, stringResource(R.string.pdf_note_edit))
            }
        }
        SelectionContainer { Text(quote.text, color = colors.onChromeVariant, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider(color = colors.onChromeVariant.copy(alpha = 0.2f))
        SelectionContainer { Text(quote.note.orEmpty(), style = MaterialTheme.typography.bodyLarge) }
    }
}
