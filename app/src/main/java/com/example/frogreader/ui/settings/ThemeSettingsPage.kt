package com.example.frogreader.ui.settings

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.theme.animatedColorScheme

@Composable
internal fun ThemeSettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme = settings.effectiveTheme(systemDark)

    MaterialTheme(colorScheme = animatedColorScheme(MaterialTheme.colorScheme)) {
        SettingsPageScaffold(
            title = stringResource(R.string.settings_theme_title),
            onBack = onBack,
        ) {
            Text(
                text = stringResource(R.string.settings_theme_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp),
            )
            ThemePreviewPicker(
                settings = settings,
                effectiveTheme = effectiveTheme,
                onSelect = { theme ->
                    onUpdate { current -> current.copy(theme = theme, followSystemTheme = false) }
                },
            )

            Spacer(Modifier.height(24.dp))
            SettingsSectionLabel(stringResource(R.string.settings_theme_automatic))
            SettingsCard {
                ThemeAutomaticControls(settings = settings, onUpdate = onUpdate)
            }

            SettingsHelper(
                when {
                    settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        stringResource(R.string.settings_theme_helper_dynamic)
                    }
                    settings.followSystemTheme -> {
                        stringResource(R.string.settings_theme_helper_system)
                    }
                    else -> {
                        stringResource(R.string.settings_theme_helper_manual)
                    }
                },
            )
        }
    }
}
