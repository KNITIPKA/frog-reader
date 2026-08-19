package com.example.frogreader.ui.settings

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.LightThemeDefault
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.theme.FrogExtraColors
import com.example.frogreader.ui.theme.appColorSchemeFor
import com.example.frogreader.ui.theme.descriptionRes
import com.example.frogreader.ui.theme.displayNameRes
import com.example.frogreader.ui.theme.frogColorsFor

@Composable
internal fun ThemeSettingsPage(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme = settings.effectiveTheme(systemDark)

    SettingsPageScaffold(
        title = stringResource(R.string.settings_theme_title),
        onBack = onBack,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTheme.entries.forEach { theme ->
                val previewScheme = appColorSchemeFor(theme, settings.dynamicColor)
                ThemePreviewOption(
                    theme = theme,
                    scheme = previewScheme,
                    frog = frogColorsFor(theme, previewScheme, settings.dynamicColor),
                    materialYou = settings.dynamicColor &&
                        theme != AppTheme.SEPIA &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    selected = theme == effectiveTheme,
                    onClick = {
                        onUpdate { current ->
                            current.copy(theme = theme, followSystemTheme = false)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SettingsSectionLabel(stringResource(R.string.settings_theme_automatic))
        SettingsCard {
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
                onCheckedChange = { enabled ->
                    onUpdate { it.copy(followSystemTheme = enabled) }
                },
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
                    onCheckedChange = { enabled ->
                        onUpdate { it.copy(dynamicColor = enabled) }
                    },
                )
            }
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

@Composable
private fun ThemePreviewOption(
    theme: AppTheme,
    scheme: ColorScheme,
    frog: FrogExtraColors,
    materialYou: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = when {
        materialYou && theme == AppTheme.WHITE -> R.string.theme_light_material_note
        materialYou && theme == AppTheme.OLED -> R.string.theme_midnight_material_note
        else -> theme.descriptionRes
    }
    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) { }
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ThemePreview(
                scheme = scheme,
                frog = frog,
                selected = selected,
                modifier = Modifier.fillMaxWidth(),
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(theme.displayNameRes),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            ),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(description),
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
        )
    }
}

@Composable
private fun ThemePreview(
    scheme: ColorScheme,
    frog: FrogExtraColors,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val borderWidth = if (selected) 3.dp else 1.dp
    val borderColor = if (selected) scheme.primary else scheme.outlineVariant
    Surface(
        modifier = modifier
            .aspectRatio(0.60f),
        shape = shape,
        color = scheme.background,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(Modifier.fillMaxSize()) {
            MiniLibraryHeader(frog)
            Spacer(Modifier.height(4.dp))
            MiniHeroCard(scheme, frog)
            Spacer(Modifier.height(4.dp))
            MiniLibrarySection(scheme)
            Spacer(Modifier.height(3.dp))
            MiniBookGrid(scheme, frog)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 7.dp, bottom = 7.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(15.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniLibraryHeader(frog: FrogExtraColors) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Brush.verticalGradient(listOf(frog.headerTop, frog.headerBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(17.dp)
                .padding(horizontal = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(frog.glass)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = frog.ink2,
                    modifier = Modifier.size(8.dp),
                )
                Spacer(Modifier.width(3.dp))
                Box(
                    Modifier
                        .width(24.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(frog.ink2.copy(alpha = 0.62f)),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(frog.glass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = frog.ink2,
                    modifier = Modifier.size(8.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniHeroCard(scheme: ColorScheme, frog: FrogExtraColors) {
    Row(
        modifier = Modifier
            .padding(horizontal = 7.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerLowest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(scheme.tertiaryContainer, scheme.primaryContainer),
                    ),
                ),
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier
                    .width(30.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(frog.ink.copy(alpha = 0.82f)),
            )
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .width(22.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.62f)),
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(frog.chip),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.42f)
                        .fillMaxHeight()
                        .background(scheme.primary),
                )
            }
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(scheme.primary),
            )
        }
    }
}

@Composable
private fun MiniLibrarySection(scheme: ColorScheme) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(23.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(scheme.onBackground.copy(alpha = 0.76f)),
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(scheme.primary),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.45f)),
            )
        }
    }
}

@Composable
private fun MiniBookGrid(scheme: ColorScheme, frog: FrogExtraColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        MiniBookCover(
            top = scheme.primaryContainer,
            bottom = scheme.primary.copy(alpha = 0.72f),
            badge = scheme.inverseSurface,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        MiniBookCover(
            top = scheme.tertiaryContainer,
            bottom = scheme.tertiary.copy(alpha = 0.68f),
            badge = scheme.inverseSurface,
            modifier = Modifier.weight(1f).fillMaxHeight(0.94f),
        )
        MiniShelf(
            scheme = scheme,
            frog = frog,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun MiniBookCover(
    top: androidx.compose.ui.graphics.Color,
    bottom: androidx.compose.ui.graphics.Color,
    badge: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Brush.verticalGradient(listOf(top, bottom))),
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .width(9.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(badge.copy(alpha = 0.78f)),
        )
    }
}

@Composable
private fun MiniShelf(
    scheme: ColorScheme,
    frog: FrogExtraColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(frog.folder)
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            scheme.primaryContainer to scheme.secondaryContainer,
            scheme.tertiaryContainer to scheme.surfaceContainerHighest,
        ).forEach { colors ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.first),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.second),
                )
            }
        }
    }
}
