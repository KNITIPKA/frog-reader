package com.example.frogreader.ui.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.ThemePreviewCoverColors
import com.example.frogreader.ui.theme.animatedColorScheme
import com.example.frogreader.ui.theme.appColorSchemeFor
import com.example.frogreader.ui.theme.displayNameRes
import com.example.frogreader.ui.theme.floatingSurface
import com.example.frogreader.ui.theme.isDark
import com.example.frogreader.ui.theme.themePreviewCoverColorsFor

@Composable
internal fun ThemePreviewPicker(
    settings: AppSettings,
    effectiveTheme: AppTheme,
    onSelect: (AppTheme) -> Unit,
    enabled: Boolean = true,
) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val useRows = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.4f
        if (useRows) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTheme.entries.forEach { theme ->
                    ThemePreviewOption(
                        theme, settings, effectiveTheme, horizontal = true,
                        onSelect = onSelect, modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    )
                }
            }
        } else {
            Row(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTheme.entries.forEach { theme ->
                    ThemePreviewOption(
                        theme, settings, effectiveTheme, horizontal = false,
                        onSelect = onSelect, modifier = Modifier.weight(1f), enabled = enabled,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewOption(
    theme: AppTheme,
    settings: AppSettings,
    effectiveTheme: AppTheme,
    horizontal: Boolean,
    onSelect: (AppTheme) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
) {
    val target = appColorSchemeFor(theme, settings.dynamicColor)
    val scheme = animatedColorScheme(target)
    val selected = theme == effectiveTheme
    val selection by animateFloatAsState(
        if (selected) 1f else 0f, tween(220), label = "themePreviewSelection",
    )
    val border by animateColorAsState(
        if (selected) target.primary else target.outlineVariant.copy(alpha = .24f),
        tween(220), label = "themePreviewOutline",
    )
    val covers = themePreviewCoverColorsFor(theme, scheme, settings.dynamicColor)
    val materialYou = settings.dynamicColor && theme != AppTheme.SEPIA &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val description = stringResource(
        when {
            materialYou -> R.string.theme_preview_material_note
            theme == AppTheme.WHITE -> R.string.theme_preview_light_note
            theme == AppTheme.SEPIA -> R.string.theme_preview_beige_note
            else -> R.string.theme_preview_midnight_note
        },
    )
    val label: @Composable (Modifier) -> Unit = { labelModifier ->
        Text(
            text = stringResource(theme.displayNameRes),
            modifier = labelModifier,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (horizontal) TextAlign.Start else TextAlign.Center,
        )
    }
    val optionModifier = modifier
        .semantics(mergeDescendants = true) { contentDescription = description }
        // The rounded specimen border supplies feedback without washing over the label.
        .selectable(
            selected = selected,
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.RadioButton,
            onClick = { onSelect(theme) },
        )
    val specimen: @Composable (Modifier) -> Unit = { specimenModifier ->
        MiniLibrary(
            scheme = scheme,
            covers = covers,
            dark = theme.isDark(),
            border = BorderStroke(.5.dp + 1.dp * selection, border),
            modifier = specimenModifier,
        )
    }
    if (horizontal) {
        Row(optionModifier, verticalAlignment = Alignment.CenterVertically) {
            specimen(Modifier.width(96.dp))
            label(Modifier.weight(1f).padding(horizontal = 16.dp))
        }
    } else {
        Column(optionModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            specimen(Modifier.fillMaxWidth())
            label(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** A fixed, text-free specimen makes the palette readable even at miniature size. */
@Composable
private fun MiniLibrary(
    scheme: ColorScheme,
    covers: ThemePreviewCoverColors,
    dark: Boolean,
    border: BorderStroke,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.aspectRatio(.8f).clearAndSetSemantics { }) {
        // Scale the entire drawing together; device font size affects only the real labels.
        val unit = maxWidth / 114f
        val cardSurface = if (dark) scheme.surfaceContainerHighest else scheme.floatingSurface
        val placeholder = lerp(cardSurface, scheme.onSurface, if (dark) .24f else .1f)
        val selectedTab = if (dark) scheme.secondaryContainer else scheme.primaryContainer
        val onSelectedTab = if (dark) scheme.onSecondaryContainer else scheme.onPrimaryContainer
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(unit * 16),
            color = if (dark) scheme.surfaceContainerHigh else scheme.background,
            border = border,
            shadowElevation = 2.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxSize().padding(
                    start = unit * 9, end = unit * 9,
                    top = unit * 14, bottom = unit * 9.5f,
                ),
                verticalArrangement = Arrangement.spacedBy(unit * 7),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth().height(unit * 46),
                    shape = RoundedCornerShape(unit * 10),
                    color = cardSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        Modifier.padding(unit * 7),
                        horizontalArrangement = Arrangement.spacedBy(unit * 6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PreviewBlock(covers.featured, unit * 5, Modifier.width(unit * 26).fillMaxHeight())
                        Column(Modifier.weight(1f)) {
                            PreviewBlock(placeholder, unit * 3, Modifier.fillMaxWidth().height(unit * 4.5f))
                            Spacer(Modifier.height(unit * 4))
                            PreviewBlock(placeholder, unit * 3, Modifier.fillMaxWidth(.65f).height(unit * 4.5f))
                            Spacer(Modifier.height(unit * 5))
                            Box(
                                Modifier.fillMaxWidth().height(unit * 4.5f)
                                    .clip(RoundedCornerShape(unit * 3))
                                    .background(placeholder),
                            ) {
                                PreviewBlock(scheme.primary, unit * 3, Modifier.fillMaxWidth(.65f).fillMaxHeight())
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().height(unit * 33),
                    horizontalArrangement = Arrangement.spacedBy(unit * 5),
                ) {
                    listOf(covers.first, covers.second, covers.third).forEach { color ->
                        PreviewBlock(color, unit * 6, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().height(unit * 26),
                    shape = RoundedCornerShape(50),
                    color = cardSurface,
                    tonalElevation = 0.dp,
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = unit * 8, vertical = unit * 4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(unit * 6),
                    ) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight()
                                .clip(RoundedCornerShape(50)).background(selectedTab),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null,
                                tint = onSelectedTab, modifier = Modifier.size(unit * 13),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.PersonOutline, contentDescription = null,
                                tint = scheme.onSurfaceVariant, modifier = Modifier.size(unit * 14),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBlock(color: Color, radius: Dp, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(color))
}
