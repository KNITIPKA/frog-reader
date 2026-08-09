package com.example.frogreader.ui.library

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.R
import com.example.frogreader.data.DuplicateMatch
import com.example.frogreader.data.FolderScanner

/**
 * What was found in the folder the user picked, and which of it to add.
 *
 * A list rather than a wall of buttons because the interesting question is not
 * "add this one?" but "which of these do I not already have?" — so every row
 * carries its cover, author, format and size, and the ones already in the
 * library say so instead of quietly being added twice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScanFolderScreen(
    treeUri: Uri,
    onDismiss: () -> Unit,
    onPickAnotherFolder: () -> Unit,
    /** Reported once a batch finishes, so the library can say what happened. */
    onFinished: (added: Int, failed: Int) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as FrogReaderApp

    // Keyed on the folder: picking a different one starts a different scan
    // rather than appending to this one's results.
    val state = remember(treeUri) {
        ScanFolderState(
            repository = app.bookRepository,
            scope = scope,
            cacheDir = context.cacheDir,
        )
    }

    LaunchedEffect(treeUri) { state.start(context, treeUri) }
    DisposableEffect(treeUri) { onDispose { state.dispose() } }

    val visible = state.visibleRows
    val importing = state.importing

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = FolderScanner.folderName(treeUri)
                                    ?: stringResource(R.string.scan_folder_fallback),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (state.scanning) {
                                    stringResource(R.string.scan_looking, state.rows.size)
                                } else {
                                    stringResource(
                                        R.string.scan_found,
                                        state.rows.size,
                                        state.alreadyInLibraryCount,
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.dup_action_cancel),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (importing != null) {
                            Text(
                                text = stringResource(
                                    R.string.scan_adding,
                                    importing.done + 1,
                                    importing.total,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = {
                                    if (importing.total == 0) 0f
                                    else importing.done.toFloat() / importing.total
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                state.addSelected { added, failed ->
                                    // Close on success and let the library
                                    // report it: the books are there now, and
                                    // staying on a list of files that are all
                                    // marked "already added" helps nobody. A
                                    // run with failures stays put so the rows
                                    // that went wrong can be seen and retried.
                                    onFinished(added, failed)
                                    if (failed == 0) onDismiss()
                                }
                            },
                            enabled = state.selectedCount > 0 && importing == null,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.scan_add_n, state.selectedCount))
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when {
                    state.accessLost -> ScanEmpty(
                        icon = { Icon(Icons.Rounded.FolderOff, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                        title = stringResource(R.string.scan_access_lost),
                        subtitle = stringResource(R.string.scan_access_lost_hint),
                        action = {
                            FilledTonalButton(
                                onClick = onPickAnotherFolder,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(stringResource(R.string.scan_pick_again))
                            }
                        },
                    )

                    state.rows.isEmpty() && state.scanning -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(Modifier.size(48.dp))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.scan_title),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    state.rows.isEmpty() -> ScanEmpty(
                        icon = { Icon(Icons.Rounded.FolderOff, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) },
                        title = stringResource(R.string.scan_empty),
                        subtitle = stringResource(R.string.scan_empty_hint),
                        action = {
                            FilledTonalButton(
                                onClick = onPickAnotherFolder,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(stringResource(R.string.scan_pick_again))
                            }
                        },
                    )

                    else -> {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { state.query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = {
                                Text(
                                    stringResource(R.string.scan_search_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = {
                                if (state.query.isNotEmpty()) {
                                    IconButton(onClick = { state.query = "" }) {
                                        Icon(
                                            Icons.Rounded.Clear,
                                            contentDescription = stringResource(R.string.reader_search_clear),
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                        )

                        val allTicked = visible.isNotEmpty() && visible.all { !it.selectable || it.selected }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = allTicked,
                                onCheckedChange = { state.setAllSelected(it) },
                                enabled = importing == null,
                            )
                            Text(
                                text = stringResource(R.string.scan_select_all),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.scanning) {
                                LoadingIndicator(Modifier.size(18.dp))
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )

                        if (visible.isEmpty()) {
                            ScanEmpty(
                                icon = {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(56.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                },
                                title = stringResource(R.string.scan_empty_search),
                                subtitle = null,
                                action = null,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 16.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(visible, key = { it.id }) { row ->
                                    ScanRowCard(
                                        row = row,
                                        enabled = importing == null,
                                        onToggle = {
                                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            state.toggleSelected(row.id)
                                        },
                                        onRetry = { state.retry(row.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val conflict by state.conflicts.current.collectAsStateWithLifecycle()
        conflict?.let { pending ->
            DuplicateBookDialog(
                conflict = pending,
                onChoice = { choice, applyToRest -> state.answerConflict(choice, applyToRest) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanRowCard(
    row: ScanRow,
    enabled: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val failed = row.state == ScanRowState.FAILED
    Surface(
        onClick = onToggle,
        enabled = enabled && row.selectable,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(width = 52.dp, height = 74.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (row.cover != null) {
                        AsyncImage(
                            model = row.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (failed) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.author ?: stringResource(R.string.import_author_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${row.format.name} · ${formatFileSize(row.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                when (row.state) {
                    ScanRowState.IN_LIBRARY -> Text(
                        text = stringResource(
                            if (row.match == DuplicateMatch.SAME_FILE) {
                                R.string.scan_state_in_library
                            } else {
                                R.string.scan_state_similar
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )

                    ScanRowState.FAILED -> Text(
                        text = stringResource(R.string.scan_state_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }
            }

            Spacer(Modifier.width(8.dp))

            when (row.state) {
                ScanRowState.PENDING -> LoadingIndicator(Modifier.size(20.dp))
                ScanRowState.FAILED -> IconButton(onClick = onRetry, enabled = enabled) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.scan_retry),
                    )
                }

                else -> Checkbox(
                    checked = row.selected,
                    onCheckedChange = { onToggle() },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun ScanEmpty(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    action: (@Composable () -> Unit)?,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            icon()
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}
