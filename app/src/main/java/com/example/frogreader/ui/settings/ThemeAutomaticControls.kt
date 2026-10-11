package com.example.frogreader.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.LightThemeDefault
import com.example.frogreader.ui.theme.displayNameRes

/** Shared automatic theme choices for Settings and the first-launch setup. */
@Composable
internal fun ThemeAutomaticControls(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsSwitchRow(
            icon = Icons.Rounded.Brightness4,
            title = stringResource(R.string.settings_follow_system),
            subtitle = if (settings.followSystemTheme) {
                stringResource(R.string.settings_follow_system_on_subtitle)
            } else {
                stringResource(
                    R.string.settings_follow_system_off_subtitle,
                    stringResource(settings.theme.displayNameRes),
                )
            },
            checked = settings.followSystemTheme,
            onCheckedChange = { followSystem ->
                onUpdate { it.copy(followSystemTheme = followSystem) }
            },
            enabled = enabled,
        )

        AnimatedVisibility(
            visible = settings.followSystemTheme,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
        ) {
            Column {
                SettingsDivider()
                SettingsControlRow(
                    icon = Icons.Rounded.LightMode,
                    title = stringResource(R.string.settings_light_theme_default),
                    subtitle = stringResource(R.string.settings_light_theme_default_subtitle),
                    selected = settings.lightThemeDefault,
                    options = listOf(
                        LightThemeDefault.LIGHT to stringResource(R.string.theme_light),
                        LightThemeDefault.BEIGE to stringResource(R.string.theme_beige),
                    ),
                    onSelected = { selected ->
                        onUpdate { it.copy(lightThemeDefault = selected) }
                    },
                    enabled = enabled,
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingsDivider()
            SettingsSwitchRow(
                icon = Icons.Rounded.Colorize,
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_subtitle),
                checked = settings.dynamicColor,
                onCheckedChange = { dynamicColor ->
                    onUpdate { it.copy(dynamicColor = dynamicColor) }
                },
                enabled = enabled,
            )
        }
    }
}
