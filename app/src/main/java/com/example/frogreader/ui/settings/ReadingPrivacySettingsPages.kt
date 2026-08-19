package com.example.frogreader.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.frogreader.R
import com.example.frogreader.data.AppLockDelay
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.ui.lock.canUseAppLock

@Composable
internal fun ReadingSettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScaffold(
        title = stringResource(R.string.settings_reading_title),
        onBack = onBack,
    ) {
        SettingsSectionLabel(stringResource(R.string.settings_while_reading))
        SettingsCard {
            SettingsControlRow(
                icon = Icons.AutoMirrored.Rounded.Login,
                title = stringResource(R.string.settings_startup_title),
                subtitle = stringResource(R.string.settings_startup_subtitle),
                selected = settings.startupDestination,
                options = listOf(
                    StartupDestination.LIBRARY to stringResource(R.string.nav_library),
                    StartupDestination.LAST_BOOK to stringResource(R.string.settings_startup_last_book),
                ),
                onSelected = { selected ->
                    onUpdate { it.copy(startupDestination = selected) }
                },
            )
            SettingsDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.Visibility,
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                checked = settings.keepScreenOn,
                onCheckedChange = { checked ->
                    onUpdate { it.copy(keepScreenOn = checked) }
                },
            )
            SettingsDivider()
            SettingsSwitchRow(
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                title = stringResource(R.string.settings_volume_keys),
                subtitle = stringResource(R.string.settings_volume_keys_subtitle),
                checked = settings.volumeKeyPaging,
                onCheckedChange = { checked ->
                    onUpdate { it.copy(volumeKeyPaging = checked) }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
        SettingsSectionLabel(stringResource(R.string.settings_section_feedback))
        SettingsCard {
            SettingsSwitchRow(
                icon = Icons.Rounded.Vibration,
                title = stringResource(R.string.settings_haptics),
                subtitle = stringResource(R.string.settings_haptics_subtitle),
                checked = settings.haptics,
                onCheckedChange = { checked ->
                    onUpdate { it.copy(haptics = checked) }
                },
            )
        }
        SettingsHelper(stringResource(R.string.settings_reading_helper))
    }
}

@Composable
internal fun PrivacySettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val biometricsAvailable = rememberBiometricsAvailable()

    SettingsPageScaffold(
        title = stringResource(R.string.settings_privacy_title),
        onBack = onBack,
    ) {
        SettingsSectionLabel(stringResource(R.string.settings_app_lock_section))
        SettingsCard {
            SettingsSwitchRow(
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
                enabled = biometricsAvailable || settings.appLock,
                onCheckedChange = { checked ->
                    onUpdate { it.copy(appLock = checked) }
                },
            )

            AnimatedVisibility(
                visible = settings.appLock,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                Column {
                    SettingsDivider()
                    SettingsControlRow(
                        icon = Icons.Rounded.Timer,
                        title = stringResource(R.string.settings_lock_delay_title),
                        subtitle = stringResource(R.string.settings_lock_delay_subtitle),
                        selected = settings.appLockDelay,
                        options = listOf(
                            AppLockDelay.IMMEDIATE to stringResource(
                                R.string.settings_lock_delay_immediate_short,
                            ),
                            AppLockDelay.ONE_MINUTE to stringResource(
                                R.string.settings_lock_delay_one_minute_short,
                            ),
                            AppLockDelay.FIFTEEN_MINUTES to stringResource(
                                R.string.settings_lock_delay_fifteen_minutes_short,
                            ),
                        ),
                        onSelected = { selected ->
                            onUpdate { it.copy(appLockDelay = selected) }
                        },
                    )
                }
            }
        }
        SettingsHelper(stringResource(R.string.settings_privacy_helper))
    }
}

@Composable
private fun rememberBiometricsAvailable(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var available by remember(context) { mutableStateOf(canUseAppLock(context)) }

    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                available = canUseAppLock(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return available
}
