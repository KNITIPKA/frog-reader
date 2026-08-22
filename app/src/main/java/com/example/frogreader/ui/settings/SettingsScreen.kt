package com.example.frogreader.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.frogreader.R
import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.theme.displayNameRes
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class SettingsDestination {
    ROOT,
    THEME,
    READING,
    PRIVACY,
    BACKUP,
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    backupViewModel: BackupViewModel = viewModel(factory = BackupViewModel.Factory),
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val backupFrequency by backupViewModel.frequency.collectAsStateWithLifecycle()
    val lastBackupAt by backupViewModel.lastBackupAt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var destination by rememberSaveable { mutableStateOf(SettingsDestination.ROOT) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    BackHandler(destination != SettingsDestination.ROOT) {
        destination = SettingsDestination.ROOT
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { settingsTransition() },
            label = "settingsPage",
        ) { page ->
            when (page) {
                SettingsDestination.ROOT -> SettingsEntryPage(
                    settings = settings,
                    backupFrequency = backupFrequency,
                    lastBackupAt = lastBackupAt,
                    onBack = onBack,
                    onOpenTheme = { destination = SettingsDestination.THEME },
                    onOpenReading = { destination = SettingsDestination.READING },
                    onOpenPrivacy = { destination = SettingsDestination.PRIVACY },
                    onOpenBackup = { destination = SettingsDestination.BACKUP },
                    onOpenAbout = { showAbout = true },
                )

                SettingsDestination.THEME -> ThemeSettingsPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onBack = { destination = SettingsDestination.ROOT },
                )

                SettingsDestination.READING -> ReadingSettingsPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onBack = { destination = SettingsDestination.ROOT },
                )

                SettingsDestination.PRIVACY -> PrivacySettingsPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                    onBack = { destination = SettingsDestination.ROOT },
                )

                SettingsDestination.BACKUP -> BackupSettingsPage(
                    settings = settings,
                    onUpdate = viewModel::update,
                    viewModel = backupViewModel,
                    onBack = { destination = SettingsDestination.ROOT },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenFailed = { message ->
                showAbout = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
        )
    }
}

private fun AnimatedContentTransitionScope<SettingsDestination>.settingsTransition(): ContentTransform {
    val forward = targetState != SettingsDestination.ROOT
    val direction = if (forward) {
        AnimatedContentTransitionScope.SlideDirection.Left
    } else {
        AnimatedContentTransitionScope.SlideDirection.Right
    }
    return (slideIntoContainer(towards = direction) + fadeIn()) togetherWith
        (slideOutOfContainer(towards = direction) + fadeOut())
}

@Composable
private fun SettingsEntryPage(
    settings: AppSettings,
    backupFrequency: BackupFrequency,
    lastBackupAt: Long?,
    onBack: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme = settings.effectiveTheme(systemDark)
    val versionName = installedVersionName()

    SettingsPageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
    ) {
        SettingsCard {
            SettingsNavigationRow(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.settings_theme_title),
                subtitle = if (settings.followSystemTheme) {
                    stringResource(
                        R.string.settings_theme_summary_system,
                        stringResource(effectiveTheme.displayNameRes),
                    )
                } else {
                    stringResource(
                        R.string.settings_theme_summary_fixed,
                        stringResource(settings.theme.displayNameRes),
                    )
                },
                onClick = onOpenTheme,
                trailing = { ThemeSwatch() },
            )
            SettingsDivider()
            SettingsNavigationRow(
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                title = stringResource(R.string.settings_reading_title),
                subtitle = readingSummary(settings),
                onClick = onOpenReading,
            )
        }

        Spacer(Modifier.height(24.dp))

        SettingsCard {
            SettingsNavigationRow(
                icon = Icons.Rounded.Fingerprint,
                title = stringResource(R.string.settings_app_lock_title),
                subtitle = lockSummary(settings),
                onClick = onOpenPrivacy,
            )
            SettingsDivider()
            SettingsNavigationRow(
                icon = Icons.Rounded.CloudUpload,
                title = stringResource(R.string.settings_backup_title),
                subtitle = backupSummary(lastBackupAt, backupFrequency),
                onClick = onOpenBackup,
                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(Modifier.height(24.dp))

        SettingsCard {
            SettingsNavigationRow(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.settings_about_summary, versionName),
                onClick = onOpenAbout,
            )
        }
    }
}

@Composable
private fun readingSummary(settings: AppSettings): String {
    val enabled = buildList {
        if (settings.keepScreenOn) add(stringResource(R.string.settings_summary_screen))
        if (settings.volumeKeyPaging) add(stringResource(R.string.settings_summary_volume))
        if (settings.haptics) add(stringResource(R.string.settings_summary_vibration))
    }
    return enabled.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        ?: stringResource(R.string.settings_summary_helpers_off)
}

@Composable
private fun lockSummary(settings: AppSettings): String {
    if (!settings.appLock) return stringResource(R.string.settings_lock_off)
    val delay = when (settings.appLockDelay) {
        AppLockDelay.IMMEDIATE -> stringResource(R.string.settings_lock_delay_immediate)
        AppLockDelay.ONE_MINUTE -> stringResource(R.string.settings_lock_delay_one_minute)
        AppLockDelay.FIFTEEN_MINUTES -> stringResource(R.string.settings_lock_delay_fifteen_minutes)
    }
    return stringResource(R.string.settings_lock_on_summary, delay)
}

@Composable
private fun backupSummary(lastBackupAt: Long?, frequency: BackupFrequency): String {
    val schedule = when (frequency) {
        BackupFrequency.OFF -> stringResource(R.string.backup_schedule_off)
        BackupFrequency.DAILY -> stringResource(R.string.backup_schedule_daily)
        BackupFrequency.WEEKLY -> stringResource(R.string.backup_schedule_weekly)
    }
    return lastBackupAt?.let {
        stringResource(
            R.string.settings_backup_summary,
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
            schedule.lowercase(),
        )
    } ?: stringResource(R.string.settings_backup_summary_never, schedule.lowercase())
}

@Composable
private fun ThemeSwatch() {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(scheme.surface),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(scheme.primary),
        )
    }
}

@Composable
private fun installedVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}

private data class AboutLink(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: Int,
    val subtitle: Int,
    val uri: String,
)

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenFailed: (String) -> Unit,
) {
    val context = LocalContext.current
    val versionName = installedVersionName()
    val failedMessage = stringResource(R.string.settings_link_failed)
    val links = listOf(
        AboutLink(
            icon = Icons.AutoMirrored.Rounded.Send,
            title = R.string.settings_about_telegram,
            subtitle = R.string.settings_about_telegram_subtitle,
            uri = "https://t.me/frogreader",
        ),
        AboutLink(
            icon = Icons.Rounded.Code,
            title = R.string.settings_about_github,
            subtitle = R.string.settings_about_github_subtitle,
            uri = "https://github.com/KNITIPKA/frog-reader",
        ),
        AboutLink(
            icon = Icons.Rounded.NewReleases,
            title = R.string.settings_about_releases,
            subtitle = R.string.settings_about_releases_subtitle,
            uri = "https://github.com/KNITIPKA/frog-reader/releases",
        ),
        AboutLink(
            icon = Icons.Rounded.ReportProblem,
            title = R.string.settings_about_issues,
            subtitle = R.string.settings_about_issues_subtitle,
            uri = "https://github.com/KNITIPKA/frog-reader/issues",
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SettingsCardShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        icon = {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.settings_about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        text = {
            Column {
                links.forEachIndexed { index, link ->
                    AboutLinkRow(
                        link = link,
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, link.uri.toUri()))
                            }.onFailure { onOpenFailed(failedMessage) }
                        },
                    )
                    if (index != links.lastIndex) SettingsDivider(start = 38.dp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.settings_about_footnote),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close))
            }
        },
    )
}

@Composable
private fun AboutLinkRow(link: AboutLink, onClick: () -> Unit) {
    SettingsNavigationRow(
        icon = link.icon,
        title = stringResource(link.title),
        subtitle = stringResource(link.subtitle),
        onClick = onClick,
        iconContainer = Color.Transparent,
        modifier = Modifier.padding(horizontal = 0.dp),
        showChevron = false,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        },
    )
}
