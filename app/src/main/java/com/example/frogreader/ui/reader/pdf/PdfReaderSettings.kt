package com.example.frogreader.ui.reader.pdf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.ReaderSettings
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.data.model.PdfToolbarAppearance
import com.example.frogreader.ui.reader.*
import com.example.frogreader.ui.theme.readingPageBackground

/** Fixed-layout preferences, using the same controls as the reflowable reader. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfReaderSettings(
    colors: ReaderColors,
    appSettings: AppSettings? = null,
    onUpdateAppSettings: ((AppSettings) -> AppSettings) -> Unit = {},
    brightness: Float? = null,
    onBrightness: (Float?) -> Unit = {},
    onBookInfo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    settings: ReaderSettings? = null,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit = {},
) {
    val context = LocalContext.current
    fun readSystemBrightness() = runCatching {
        android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
    }.getOrDefault(0.5f)
    var systemBrightness by remember(context) { mutableFloatStateOf(readSystemBrightness()) }
    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemBrightness = readSystemBrightness() }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(
                android.provider.Settings.System.SCREEN_BRIGHTNESS), false, observer)
        }.isSuccess
        onDispose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }
    val scroll = rememberScrollState()
    val fontScale = LocalDensity.current.fontScale
    Box(modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 12.dp)) {
            if (settings != null) {
                val appTheme = appSettings?.effectiveTheme(isSystemInDarkTheme()) ?: AppTheme.WHITE
                val chosen = settings.pdfTheme ?: appTheme
                PanelSectionLabel(stringResource(R.string.pdf_reader_theme), colors)
                val themes = AppTheme.entries
                val labels = listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_beige),
                    stringResource(R.string.theme_midnight))
                val swatch: @Composable (Int) -> Unit = { index ->
                    Box(Modifier.size(18.dp).clip(CircleShape).background(themes[index].readingPageBackground)
                        .border(1.dp, colors.onChrome.copy(alpha = 0.25f), CircleShape))
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 280.dp || fontScale > 1.25f) {
                        SettingsGroup(colors) {
                            themes.forEachIndexed { index, theme ->
                                ChipRow(listOf(labels[index]), if (chosen == theme) 0 else -1, colors,
                                    onSelect = { onUpdateSettings { it.copy(pdfTheme = theme) } },
                                    leading = { swatch(index) })
                            }
                        }
                    } else {
                        ChipRow(labels, themes.indexOf(chosen), colors,
                            onSelect = { index -> onUpdateSettings { it.copy(pdfTheme = themes[index]) } }, leading = swatch)
                    }
                }
                Text(stringResource(R.string.pdf_reader_theme_hint), color = colors.onChromeVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(24.dp))
            }

            val brightnessLabel = stringResource(R.string.reader_brightness)
            val brightnessValue = brightnessPosition(brightness ?: systemBrightness)
            PanelSectionLabel(brightnessLabel, colors)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WbSunny, null, tint = colors.onChromeVariant, modifier = Modifier.size(16.dp))
                SettingSlider(brightnessValue, { onBrightness(brightnessFromPosition(it)) }, colors,
                    Modifier.weight(1f).padding(horizontal = 12.dp).heightIn(min = 48.dp).semantics {
                        contentDescription = brightnessLabel
                        progressBarRangeInfo = ProgressBarRangeInfo(brightnessValue, 0f..1f)
                        setProgress { onBrightness(brightnessFromPosition(it)); true }
                    })
                Icon(Icons.Rounded.WbSunny, null, tint = colors.onChrome, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                val auto = brightness == null
                FilledTonalButton(onClick = { onBrightness(null) }, contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (auto) colors.selectedControl else colors.control,
                        contentColor = if (auto) colors.onSelectedControl else colors.onChrome)) {
                    Icon(Icons.Rounded.BrightnessAuto, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reader_brightness_auto), style = MaterialTheme.typography.labelMedium)
                }
            }

            if (settings != null) {
                Spacer(Modifier.height(24.dp))
                PanelSectionLabel(stringResource(R.string.pdf_reading_mode), colors)
                ChipRow(listOf(stringResource(R.string.pdf_mode_scroll), stringResource(R.string.pdf_mode_pages)),
                    if (settings.pdfSinglePage) 1 else 0, colors,
                    onSelect = { index -> onUpdateSettings { it.copy(pdfSinglePage = index == 1) } })
                Text(stringResource(if (settings.pdfSinglePage) R.string.pdf_mode_pages_hint else R.string.pdf_mode_scroll_hint),
                    color = colors.onChromeVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }

            if (appSettings != null) {
                Spacer(Modifier.height(24.dp))
                PdfToolbarSettings(colors, appSettings, onUpdateAppSettings)
                Spacer(Modifier.height(24.dp))
                PanelSectionLabel(stringResource(R.string.reader_section_device), colors, Icons.Rounded.Smartphone)
                SettingsGroup(colors) {
                    SettingToggleRow(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.settings_volume_keys),
                        colors, appSettings.volumeKeyPaging,
                        onCheckedChange = { checked -> onUpdateAppSettings { it.copy(volumeKeyPaging = checked) } },
                        subtitle = stringResource(R.string.settings_volume_keys_subtitle))
                    PanelDivider(colors)
                    SettingToggleRow(Icons.Rounded.Lightbulb, stringResource(R.string.settings_keep_screen_on),
                        colors, appSettings.keepScreenOn,
                        onCheckedChange = { checked -> onUpdateAppSettings { it.copy(keepScreenOn = checked) } })
                }
            }
            if (onBookInfo != null) {
                Spacer(Modifier.height(20.dp))
                Surface(onClick = onBookInfo, color = colors.control, contentColor = colors.onChrome,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, null, tint = colors.accent, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.book_info_title), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = colors.onChromeVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        ReaderSettingsScrollbar(scroll, colors, Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
    }
}

@Composable
private fun PdfToolbarSettings(
    colors: ReaderColors,
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val appearance = settings.pdfToolbarAppearance
    val labels = listOf(R.string.pdf_toolbar_solid, R.string.pdf_toolbar_glass, R.string.pdf_toolbar_clear)
    val hint = when (appearance) {
        PdfToolbarAppearance.SOLID -> R.string.pdf_toolbar_solid_hint
        PdfToolbarAppearance.GLASS -> if (pdfBackdropBlurSupported()) R.string.pdf_toolbar_glass_hint
            else R.string.pdf_toolbar_glass_fallback_hint
        PdfToolbarAppearance.CLEAR -> R.string.pdf_toolbar_clear_hint
    }
    PanelSectionLabel(stringResource(R.string.pdf_toolbar_appearance), colors)
    SettingsGroup(colors) {
        val label = stringResource(R.string.pdf_show_top_bar)
        SettingToggleRow(
            icon = Icons.Rounded.VerticalAlignTop,
            title = label,
            colors = colors,
            checked = settings.showPdfTopBar,
            onCheckedChange = { checked -> onUpdate { it.copy(showPdfTopBar = checked) } },
            switchModifier = Modifier.semantics { contentDescription = label },
        )
    }
    Spacer(Modifier.height(12.dp))
    val segments = SegmentedButtonDefaults.colors(
        activeContainerColor = colors.selectedControl,
        activeContentColor = colors.onSelectedControl,
        activeBorderColor = colors.selectedControl,
        inactiveContainerColor = colors.controlStrong.compositeOver(colors.chrome),
        inactiveContentColor = colors.onChrome,
        inactiveBorderColor = colors.track.compositeOver(colors.chrome),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            val mode = PdfToolbarAppearance.entries[index]
            SegmentedButton(
                selected = appearance == mode,
                onClick = {
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onUpdate { it.copy(pdfToolbarAppearance = mode) }
                },
                modifier = Modifier.weight(1f),
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                colors = segments,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                icon = {},
            ) {
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
    }
    Text(
        stringResource(hint), color = colors.onChromeVariant,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
    )
}
