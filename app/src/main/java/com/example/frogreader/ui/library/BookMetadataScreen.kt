package com.example.frogreader.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.EditableBookMetadata

@Composable
fun BookMetadataScreen(bookId: String, onBack: () -> Unit) {
    val model: BookMetadataViewModel = viewModel(factory = BookMetadataViewModel.factory(bookId))
    val state by model.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val surface = MaterialTheme.colorScheme.surface
    val isLight = surface.luminance() > 0.5f
    LaunchedEffect(isLight) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.pickCover(uri)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) model.export(uri)
    }
    MetadataEditorContent(
        state = state, onBack = onBack, onChange = model::change, onSave = model::save,
        onPickCover = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRemoveCover = model::removeCover, onResetCover = model::resetCover,
        onExport = { export.launch(model.exportName()) }, onRetry = model::load,
    )
}

/** A full navigation destination, with a persistent save area above the keyboard. */
@Composable
internal fun MetadataEditorContent(
    state: MetadataEditorState,
    onBack: () -> Unit,
    onChange: (EditableBookMetadata) -> Unit,
    onSave: () -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onResetCover: () -> Unit,
    onExport: () -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var discard by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var aboutSaving by rememberSaveable { mutableStateOf(false) }
    var errorDetails by rememberSaveable(state.error) { mutableStateOf(false) }
    val back: () -> Unit = { if (!state.busy) { if (state.dirty) discard = true else onBack() } }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = back, enabled = !state.busy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.metadata_back))
            }
            Text(stringResource(R.string.edit_book_title), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (state.book != null) {
                Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(10.dp)) {
                    Text(state.fileInfo?.label ?: state.book.format.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !state.busy) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.metadata_more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.metadata_export)) },
                        leadingIcon = { Icon(Icons.Rounded.IosShare, null) },
                        enabled = !state.loading && state.draft != null && !state.dirty,
                        onClick = { menu = false; onExport() },
                    )
                    if (state.dirty) Text(
                        stringResource(R.string.metadata_export_after_save),
                        Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.metadata_about_saving)) },
                        leadingIcon = { Icon(Icons.Rounded.Info, null) },
                        onClick = { menu = false; aboutSaving = true },
                    )
                }
            }
        }
        if (state.loading && state.draft == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (state.draft == null) {
            Column(Modifier.weight(1f).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MetadataErrorCard(state.errorAction, onDetails = { errorDetails = true })
                Button(onClick = onRetry) { Text(stringResource(R.string.metadata_retry)) }
            }
        } else {
            val draft = state.draft
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    BookIdentityHeader(
                        title = draft.title, author = draft.authors.joinToString("\n"),
                        cover = if (state.removeCover) null else state.pickedCover?.let(Uri::parse) ?: state.cover,
                        enabled = !state.busy,
                        onChangeCover = onPickCover.takeIf { state.fileInfo?.titleOnly != true },
                        onRemoveCover = onRemoveCover.takeIf { !state.removeCover && (state.pickedCover != null || state.cover != null) && state.fileInfo?.titleOnly != true },
                        onRestoreCover = onResetCover.takeIf { state.pickedCover != null || state.removeCover },
                    )
                    if (state.fileInfo?.titleOnly == true) Text(stringResource(R.string.metadata_palmdoc_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    else if (state.fileInfo?.charset != null && state.fileInfo.charset != "UTF-8") Text(stringResource(R.string.metadata_charset_hint, state.fileInfo.charset), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    MetadataSection(stringResource(R.string.metadata_basics)) {
                        MetadataField(draft.title, { onChange(draft.copy(title = it.replace('\n', ' ').replace('\r', ' '))) }, stringResource(R.string.edit_field_title), !state.busy,
                            maxLines = 6, imeAction = ImeAction.Next, error = draft.title.isBlank(),
                            hint = if (draft.title.isBlank()) stringResource(R.string.metadata_title_required) else null)
                        if (state.fileInfo?.titleOnly != true) {
                            MetadataField(draft.authors.joinToString("\n"), { onChange(draft.copy(authors = it.split('\n'))) }, stringResource(R.string.metadata_authors), !state.busy,
                                maxLines = 8, hint = stringResource(R.string.metadata_one_per_line))
                        }
                    }
                    if (state.fileInfo?.titleOnly != true) {
                        MetadataSection(stringResource(R.string.metadata_description)) {
                            MetadataField(draft.description, { onChange(draft.copy(description = it)) }, stringResource(R.string.metadata_description), !state.busy,
                                minLines = 4, maxLines = 10, hint = stringResource(R.string.metadata_search_hint), alwaysShowHint = true)
                            MetadataField(draft.genres.joinToString("\n"), { onChange(draft.copy(genres = it.split('\n'))) }, stringResource(R.string.details_genres), !state.busy,
                                maxLines = 8, hint = stringResource(R.string.metadata_one_per_line))
                        }
                        MetadataSection(stringResource(R.string.metadata_edition)) {
                            MetadataField(draft.series, { onChange(draft.copy(series = it)) }, stringResource(R.string.details_series), !state.busy)
                            MetadataField(draft.seriesNumber, { onChange(draft.copy(seriesNumber = it)) }, stringResource(R.string.metadata_series_number), !state.busy,
                                keyboard = KeyboardType.Decimal, error = draft.seriesNumber.isNotBlank() && (draft.series.isBlank() || draft.seriesNumber.toFloatOrNull()?.let { it.isFinite() && it >= 0 } != true),
                                hint = stringResource(R.string.metadata_series_hint))
                            MetadataField(draft.publisher, { onChange(draft.copy(publisher = it)) }, stringResource(R.string.details_publisher), !state.busy)
                            MetadataField(draft.year, { onChange(draft.copy(year = it)) }, stringResource(R.string.details_year), !state.busy,
                                keyboard = KeyboardType.Number, error = draft.year.isNotBlank() && !draft.year.matches(Regex("[0-9]{4}")),
                                hint = stringResource(R.string.metadata_year_hint))
                            MetadataField(draft.isbn, { onChange(draft.copy(isbn = it)) }, stringResource(R.string.details_isbn), !state.busy)
                            MetadataField(draft.translators.joinToString("\n"), { onChange(draft.copy(translators = it.split('\n'))) }, stringResource(R.string.details_translators), !state.busy,
                                maxLines = 8, hint = stringResource(R.string.metadata_one_per_line))
                            MetadataField(draft.language, { onChange(draft.copy(language = it)) }, stringResource(R.string.details_language), !state.busy,
                                imeAction = ImeAction.Done, hint = stringResource(R.string.metadata_language_hint),
                                error = draft.language.isNotBlank() && !draft.language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")))
                            if (state.book?.format == BookFormat.MOBI) Text(stringResource(R.string.metadata_mobi_hint), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Surface(color = scheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.error != null) MetadataErrorCard(state.errorAction, onDetails = { errorDetails = true })
                        Button(
                            onClick = { focus.clearFocus(); keyboard?.hide(); onSave() }, enabled = state.canSave,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp),
                        ) {
                            if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Save, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(if (state.busy) R.string.metadata_saving else R.string.metadata_save_file), fontWeight = FontWeight.Medium)
                        }
                        if (state.error == null) Row(
                            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val completed = !state.dirty && (state.saved || state.exported)
                            if (completed) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = scheme.primary)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                stringResource(when {
                                    state.dirty -> R.string.metadata_unsaved
                                    state.exported -> R.string.metadata_exported
                                    state.saved -> R.string.metadata_saved
                                    else -> R.string.metadata_file_summary
                                }),
                                style = MaterialTheme.typography.bodySmall, color = if (completed) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (aboutSaving) AlertDialog(
        onDismissRequest = { aboutSaving = false },
        icon = { Icon(Icons.Rounded.Info, null) },
        title = { Text(stringResource(R.string.metadata_about_saving)) },
        text = { Text(stringResource(R.string.metadata_file_hint)) },
        confirmButton = { TextButton(onClick = { aboutSaving = false }) { Text(stringResource(R.string.metadata_close)) } },
    )
    if (errorDetails && state.error != null) AlertDialog(
        onDismissRequest = { errorDetails = false },
        title = { Text(stringResource(R.string.metadata_error_details)) },
        text = { SelectionContainer { Text(state.error, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium) } },
        confirmButton = { TextButton(onClick = { errorDetails = false }) { Text(stringResource(R.string.metadata_close)) } },
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.metadata_discard_title)) },
        text = { Text(stringResource(R.string.metadata_discard_hint)) },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.metadata_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.metadata_keep_editing)) } },
    )
}

@Composable
private fun MetadataErrorCard(action: MetadataErrorAction, onDetails: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.errorContainer, contentColor = scheme.onErrorContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(when (action) {
                    MetadataErrorAction.LOAD -> R.string.metadata_load_error
                    MetadataErrorAction.SAVE -> R.string.metadata_save_error
                    MetadataErrorAction.EXPORT -> R.string.metadata_export_error
                }), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (action == MetadataErrorAction.SAVE) R.string.metadata_edits_kept else R.string.metadata_try_again_hint), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDetails) { Icon(Icons.Rounded.Info, stringResource(R.string.metadata_error_details)) }
        }
    }
}

@Composable
private fun MetadataSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun MetadataField(
    value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean,
    minLines: Int = 1, maxLines: Int = 1, hint: String? = null, alwaysShowHint: Boolean = false,
    error: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = if (maxLines == 1) ImeAction.Next else ImeAction.Default,
) {
    var focused by remember { mutableStateOf(false) }
    val supportingHint = hint?.takeIf { focused || error || alwaysShowHint }
    TextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(14.dp),
        singleLine = maxLines == 1, minLines = minLines, maxLines = maxLines,
        isError = error, keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        supportingText = supportingHint?.let { { Text(it) } },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}
