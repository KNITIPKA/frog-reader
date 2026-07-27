package com.example.frogreader.ui.settings

import android.hardware.biometrics.BiometricManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.frogreader.data.AppTheme

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_app_theme),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppTheme.entries.forEachIndexed { index, theme ->
                    SegmentedButton(
                        selected = settings.theme == theme,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            viewModel.update { it.copy(theme = theme) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = AppTheme.entries.size,
                        ),
                    ) {
                        Text(
                            stringResource(
                                when (theme) {
                                    AppTheme.WHITE -> R.string.settings_theme_white
                                    AppTheme.SEPIA -> R.string.settings_theme_beige
                                    AppTheme.OLED -> R.string.settings_theme_oled
                                },
                            ),
                        )
                    }
                }
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
