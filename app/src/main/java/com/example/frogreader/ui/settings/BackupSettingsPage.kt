package com.example.frogreader.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.backup.BackupRepository
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackupSettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    viewModel: BackupViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingRestore by viewModel.pending.collectAsStateWithLifecycle()
    val folder by viewModel.folder.collectAsStateWithLifecycle()
    val frequency by viewModel.frequency.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()
    val librarySummary by viewModel.librarySummary.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val snapshotsLoading by viewModel.snapshotsLoading.collectAsStateWithLifecycle()
    val snapshotsError by viewModel.snapshotsError.collectAsStateWithLifecycle()
    val snapshotsFolderUri by viewModel.snapshotsFolderUri.collectAsStateWithLifecycle()
    val operationInProgress by viewModel.operationInProgress.collectAsStateWithLifecycle()
    val folderChanging by viewModel.folderChanging.collectAsStateWithLifecycle()
    val controlsEnabled = !operationInProgress && !folderChanging
    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { viewModel.export(it, settings.backupMode) } }
    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::inspect) }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::setFolder) }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_backup_title),
        onBack = onBack,
    ) {
        BackupHero(
            state = state,
            lastBackupAt = lastBackupAt,
            summary = librarySummary,
            enabled = controlsEnabled,
            onBackup = {
                if (folder != null) {
                    viewModel.exportToFolder(settings.backupMode)
                } else {
                    createBackup.launch(viewModel.suggestedFileName())
                }
            },
            onRestore = {
                pickBackup.launch(arrayOf("application/zip", "application/octet-stream"))
            },
        )
        Spacer(Modifier.height(24.dp))
        SettingsSectionLabel(stringResource(R.string.backup_include_now_section))
        SettingsCard(modifier = Modifier.selectableGroup()) {
            BackupScopeRow(
                selected = settings.backupMode == BackupMode.DATA,
                title = stringResource(R.string.backup_mode_data_short),
                subtitle = stringResource(R.string.backup_mode_data_compact_desc),
                estimate = stringResource(R.string.backup_size_small),
                enabled = controlsEnabled,
                onClick = { onUpdate { it.copy(backupMode = BackupMode.DATA) } },
            )
            BackupScopeRow(
                selected = settings.backupMode == BackupMode.FULL,
                title = stringResource(R.string.backup_mode_full_short),
                subtitle = stringResource(R.string.backup_mode_full_compact_desc),
                estimate = stringResource(
                    R.string.backup_size_approximate,
                    formatBytes(librarySummary.fullBackupEstimatedBytes),
                ),
                enabled = controlsEnabled,
                onClick = { onUpdate { it.copy(backupMode = BackupMode.FULL) } },
            )
        }
        SettingsHelper(stringResource(R.string.backup_automatic_data_only_helper))

        Spacer(Modifier.height(24.dp))
        SettingsSectionLabel(stringResource(R.string.backup_automatic_section))
        SettingsCard {
            BackupFolderRow(
                folder = folder,
                enabled = controlsEnabled,
                onChange = { pickFolder.launch(null) },
            )
            SettingsDivider()
            SettingsControlRow(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.backup_frequency_title),
                subtitle = stringResource(
                    R.string.backup_frequency_subtitle,
                    BackupRepository.DEFAULT_KEEP,
                ),
                selected = frequency,
                options = listOf(
                    BackupFrequency.OFF to stringResource(R.string.backup_schedule_off),
                    BackupFrequency.DAILY to stringResource(R.string.backup_frequency_daily_short),
                    BackupFrequency.WEEKLY to stringResource(R.string.backup_frequency_weekly_short),
                ),
                onSelected = viewModel::setFrequency,
                enabled = folder != null && controlsEnabled,
            )
        }
        if (folder == null) {
            SettingsHelper(stringResource(R.string.backup_folder_hint))
        }

        if (folder != null) {
            Spacer(Modifier.height(24.dp))
            SnapshotHeader(count = snapshots.size)
            when {
                (snapshotsLoading || snapshotsFolderUri != folder) && snapshots.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }

                snapshotsError != null -> SnapshotError(
                    message = snapshotsError.orEmpty(),
                    onRetry = viewModel::refreshSnapshots,
                )

                snapshots.isEmpty() -> SettingsHelper(stringResource(R.string.backup_snapshots_empty))

                else -> SettingsCard {
                    snapshots.forEachIndexed { index, snapshot ->
                        SnapshotRow(
                            snapshot = snapshot,
                            enabled = controlsEnabled &&
                                !snapshotsLoading &&
                                snapshotsFolderUri == folder,
                            onRestore = { viewModel.inspect(snapshot) },
                        )
                        if (index != snapshots.lastIndex) SettingsDivider(start = 54.dp)
                    }
                }
            }
            SettingsHelper(stringResource(R.string.backup_restore_warning))
        }
    }

    pendingRestore?.let { pending ->
        RestoreConfirmDialog(
            manifest = pending.manifest,
            onDismiss = viewModel::cancelPending,
            onConfirm = viewModel::confirmRestore,
        )
    }
    BackupResultDialog(state = state, onDismiss = viewModel::dismissResult)
}

@Composable
private fun BackupHero(
    state: BackupViewModel.State,
    lastBackupAt: Long?,
    summary: BackupViewModel.LibrarySummary,
    enabled: Boolean,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val working = state as? BackupViewModel.State.Working
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 156.dp),
        shape = SettingsCardShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = lastBackupAt?.let {
                            stringResource(
                                R.string.backup_last_compact,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                            )
                        } ?: stringResource(R.string.backup_last_never_compact),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                    Text(
                        text = stringResource(
                            R.string.backup_library_summary,
                            summary.bookCount,
                            summary.quoteCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    )
                }
            }

            if (working != null) {
                BackupProgress(working)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onBackup,
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Backup, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.backup_export))
                    }
                    FilledTonalIconButton(
                        onClick = onRestore,
                        enabled = enabled,
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                                .copy(alpha = 0.74f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Restore,
                            contentDescription = stringResource(R.string.backup_restore),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupProgress(working: BackupViewModel.State.Working) {
    val progress = if (working.total > 0) {
        (working.done.toFloat() / working.total).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(
                    if (working.restoring) {
                        R.string.backup_working_restore
                    } else {
                        R.string.backup_working_export
                    },
                ) + if (working.total > 0) " ${working.done} / ${working.total}" else "",
                style = MaterialTheme.typography.labelLarge,
            )
            if (working.total > 0) {
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
        }
        if (working.total > 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun BackupScopeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    estimate: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .clip(SettingsRowShape)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainer else
                    MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.alpha(if (enabled) 1f else 0.55f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackupScopeRowContent(
                selected = selected,
                title = title,
                subtitle = subtitle,
                estimate = estimate,
            )
        }
    }
}

@Composable
private fun BackupScopeRowContent(
    selected: Boolean,
    title: String,
    subtitle: String,
    estimate: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            estimate,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupFolderRow(folder: String?, enabled: Boolean, onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconCircle(
            icon = Icons.Rounded.Folder,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.backup_folder_short), style = MaterialTheme.typography.titleMedium)
            Text(
                text = folder?.let(::folderDisplayName)
                    ?: stringResource(R.string.backup_folder_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onChange, enabled = enabled) {
            Text(stringResource(R.string.backup_folder_change))
        }
    }
}

@Composable
private fun SnapshotHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.backup_snapshots_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.backup_snapshots_count, count, BackupRepository.DEFAULT_KEEP),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SnapshotRow(
    snapshot: BackupViewModel.Snapshot,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    val timestamp = snapshot.manifest?.createdAtMillis?.takeIf { it > 0L }
        ?: snapshot.ref.modifiedAtMillis
    val mode = when (snapshot.manifest?.mode) {
        BackupMode.FULL -> stringResource(R.string.backup_scope_full_label)
        BackupMode.DATA -> stringResource(R.string.backup_scope_data_label)
        null -> stringResource(R.string.backup_scope_unknown_label)
    }
    val timestampLabel = if (timestamp > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
    } else {
        snapshot.ref.name
    }
    val restoreDescription = stringResource(
        R.string.backup_restore_snapshot_description,
        timestampLabel,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderZip,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = timestampLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                text = "$mode · ${formatBytes(snapshot.ref.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(
            onClick = onRestore,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = restoreDescription },
        ) {
            Text(stringResource(R.string.backup_restore_short))
        }
    }
}

@Composable
private fun SnapshotError(message: String, onRetry: () -> Unit) {
    SettingsCard {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.backup_snapshots_error, message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.backup_retry))
            }
        }
    }
}

@Composable
private fun RestoreConfirmDialog(
    manifest: BackupManifest,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val made = remember(manifest.createdAtMillis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(manifest.createdAtMillis))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SettingsCardShape,
        title = { Text(stringResource(R.string.backup_restore_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.backup_restore_details,
                        made,
                        manifest.bookCount,
                        manifest.quoteCount,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        if (manifest.mode == BackupMode.FULL) {
                            R.string.backup_restore_with_files
                        } else {
                            R.string.backup_restore_data_only
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.backup_restore_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.backup_restore_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

@Composable
private fun BackupResultDialog(state: BackupViewModel.State, onDismiss: () -> Unit) {
    val message = when (state) {
        is BackupViewModel.State.ExportDone -> stringResource(R.string.backup_export_done, state.books)
        is BackupViewModel.State.RestoreDone -> if (state.booksWithoutFile > 0) {
            stringResource(
                R.string.backup_restore_done_missing,
                state.books,
                state.quotes,
                state.booksWithoutFile,
            )
        } else {
            stringResource(R.string.backup_restore_done, state.books, state.quotes)
        }
        is BackupViewModel.State.Failed -> stringResource(
            if (state.restoring) R.string.backup_restore_failed else R.string.backup_failed,
            state.message,
        )
        else -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SettingsCardShape,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

private fun folderDisplayName(uri: String): String = Uri.decode(uri.substringAfterLast('/'))
    .substringAfter(':')
    .ifBlank { uri }

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val pattern = if (value >= 10.0 || unit == 0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.getDefault(), pattern, value, units[unit])
}
