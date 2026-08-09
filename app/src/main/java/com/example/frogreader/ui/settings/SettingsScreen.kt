package com.example.frogreader.ui.settings

import android.hardware.biometrics.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.ui.theme.displayNameRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import java.text.DateFormat
import java.util.Date
import android.net.Uri
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Schedule
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.backup.BackupRepository

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showThemeSheet by remember { mutableStateOf(false) }

    val backupViewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory)
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val pendingRestore by backupViewModel.pending.collectAsStateWithLifecycle()
    var showBackupModeDialog by remember { mutableStateOf(false) }
    var chosenMode by remember { mutableStateOf(BackupMode.DATA) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { backupViewModel.export(it, chosenMode) } }

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { backupViewModel.inspect(it) } }

    val backupFolder by backupViewModel.folder.collectAsStateWithLifecycle()
    val backupFrequency by backupViewModel.frequency.collectAsStateWithLifecycle()
    val lastBackupAt by backupViewModel.lastBackupAt.collectAsStateWithLifecycle()
    var showFrequencyDialog by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { backupViewModel.setFolder(it) } }

    val biometricsAvailable = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(BiometricManager::class.java)
            manager?.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            false
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.reader_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))

            // Theme lives in its own sheet now — the same composable the
            // library's gear can raise later.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { showThemeSheet = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_app_theme),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(settings.theme.displayNameRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.settings_section_reading))

            SettingSwitchRow(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                checked = settings.keepScreenOn,
                onCheckedChange = { checked ->
                    viewModel.update { it.copy(keepScreenOn = checked) }
                },
            )
            SettingSwitchRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.settings_volume_keys),
                subtitle = stringResource(R.string.settings_volume_keys_subtitle),
                checked = settings.volumeKeyPaging,
                onCheckedChange = { checked ->
                    viewModel.update { it.copy(volumeKeyPaging = checked) }
                },
            )

            SectionHeader(stringResource(R.string.settings_section_feedback))

            SettingSwitchRow(
                icon = Icons.Rounded.Vibration,
                title = stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_subtitle),
                checked = settings.haptics,
                onCheckedChange = { checked ->
                    viewModel.update { it.copy(haptics = checked) }
                },
            )

            SectionHeader(stringResource(R.string.settings_section_privacy))

            SettingSwitchRow(
                icon = Icons.Rounded.Fingerprint,
                title = stringResource(R.string.app_lock_title),
                subtitle = stringResource(
                    if (biometricsAvailable) {
                        R.string.app_lock_subtitle
                    } else {
                        R.string.app_lock_unavailable
                    },
                ),
                checked = settings.appLock,
                enabled = biometricsAvailable,
                onCheckedChange = { checked ->
                    viewModel.update { it.copy(appLock = checked) }
                },
            )

            SectionHeader(stringResource(R.string.settings_section_backup))

            SettingActionRow(
                icon = Icons.Rounded.Backup,
                title = stringResource(R.string.backup_export),
                subtitle = stringResource(R.string.backup_export_subtitle),
                enabled = backupState !is BackupViewModel.State.Working,
                onClick = { showBackupModeDialog = true },
            )
            SettingActionRow(
                icon = Icons.Rounded.Restore,
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_subtitle),
                enabled = backupState !is BackupViewModel.State.Working,
                onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
            )

            SettingActionRow(
                icon = Icons.Rounded.Folder,
                title = stringResource(R.string.backup_folder),
                subtitle = backupFolder?.let { Uri.decode(it.substringAfterLast('/')) }
                    ?: stringResource(R.string.backup_folder_none),
                enabled = true,
                onClick = { pickFolder.launch(null) },
            )
            SettingActionRow(
                icon = Icons.Rounded.Schedule,
                title = stringResource(R.string.backup_schedule),
                subtitle = stringResource(backupFrequency.labelRes()),
                enabled = backupFolder != null,
                onClick = { showFrequencyDialog = true },
            )
            Text(
                text = if (backupFolder == null) {
                    stringResource(R.string.backup_folder_hint)
                } else {
                    lastBackupAt?.let {
                        stringResource(
                            R.string.backup_last,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                        )
                    } ?: stringResource(R.string.backup_last_never)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            (backupState as? BackupViewModel.State.Working)?.let { working ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(
                            if (working.restoring) R.string.backup_working_restore
                            else R.string.backup_working_export,
                        ) + if (working.total > 0) "  ${working.done}/${working.total}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Which build is installed — bumped with every update.
            val versionName = remember {
                runCatching {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName
                }.getOrNull() ?: "?"
            }
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(48.dp))
        }
    }

    if (showThemeSheet) {
        ThemePickerSheet(
            current = settings.theme,
            onPick = { theme -> viewModel.update { it.copy(theme = theme) } },
            onDismiss = { showThemeSheet = false },
        )
    }

    if (showBackupModeDialog) {
        BackupModeDialog(
            onDismiss = { showBackupModeDialog = false },
            onPick = { mode ->
                showBackupModeDialog = false
                chosenMode = mode
                createBackup.launch(backupViewModel.suggestedFileName())
            },
        )
    }

    pendingRestore?.let { pending ->
        RestoreConfirmDialog(
            manifest = pending.manifest,
            onDismiss = { backupViewModel.cancelPending() },
            onConfirm = { backupViewModel.confirmRestore() },
        )
    }

    if (showFrequencyDialog) {
        BackupFrequencyDialog(
            current = backupFrequency,
            onDismiss = { showFrequencyDialog = false },
            onPick = {
                showFrequencyDialog = false
                backupViewModel.setFrequency(it)
            },
        )
    }

    BackupResultDialog(state = backupState, onDismiss = { backupViewModel.dismissResult() })
}

private fun BackupFrequency.labelRes(): Int = when (this) {
    BackupFrequency.OFF -> R.string.backup_schedule_off
    BackupFrequency.DAILY -> R.string.backup_schedule_daily
    BackupFrequency.WEEKLY -> R.string.backup_schedule_weekly
}

@Composable
private fun BackupFrequencyDialog(
    current: BackupFrequency,
    onDismiss: () -> Unit,
    onPick: (BackupFrequency) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_schedule_title)) },
        text = {
            Column {
                BackupFrequency.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onPick(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(option.labelRes()))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.backup_keeps, BackupRepository.DEFAULT_KEEP),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

@Composable
private fun BackupModeDialog(onDismiss: () -> Unit, onPick: (BackupMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_mode_title)) },
        text = {
            Column {
                BackupModeOption(
                    title = stringResource(R.string.backup_mode_data),
                    description = stringResource(R.string.backup_mode_data_desc),
                    onClick = { onPick(BackupMode.DATA) },
                )
                Spacer(Modifier.height(8.dp))
                BackupModeOption(
                    title = stringResource(R.string.backup_mode_full),
                    description = stringResource(R.string.backup_mode_full_desc),
                    onClick = { onPick(BackupMode.FULL) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

@Composable
private fun BackupModeOption(title: String, description: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        if (manifest.mode == BackupMode.FULL) R.string.backup_restore_with_files
                        else R.string.backup_restore_data_only,
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
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.backup_restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) }
        },
    )
}

@Composable
private fun BackupResultDialog(state: BackupViewModel.State, onDismiss: () -> Unit) {
    val message = when (state) {
        is BackupViewModel.State.ExportDone ->
            stringResource(R.string.backup_export_done, state.books)
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
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun SettingActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { value ->
                haptics.performHapticFeedback(
                    if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                )
                onCheckedChange(value)
            },
        )
    }
}
