package com.example.frogreader.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.frogreader.R
import com.example.frogreader.ui.components.FrogSegmentedControl
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.StartupDestination
import com.example.frogreader.data.effectiveTheme
import com.example.frogreader.ui.reader.readerColors
import com.example.frogreader.ui.settings.ReaderChromeStylePicker
import com.example.frogreader.ui.settings.SettingsControlHeader
import com.example.frogreader.ui.settings.SettingsCard
import com.example.frogreader.ui.settings.SettingsSectionLabel
import com.example.frogreader.ui.settings.ThemeAutomaticControls
import com.example.frogreader.ui.settings.ThemePreviewPicker

/** First-run preferences use the same specimens and palettes as Settings. */
@Composable
fun WelcomeScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onContinue: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    val theme = settings.effectiveTheme(isSystemInDarkTheme())
    val scheme = MaterialTheme.colorScheme
    val colors = remember(theme, scheme) { readerColors(theme, scheme) }
    Surface(Modifier.fillMaxSize(), color = scheme.surface) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            // One scroll container keeps every choice and Continue reachable
            // on short screens, in landscape, and with enlarged text.
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WelcomeHeader()
                WelcomeCard {
                    SettingsControlHeader(
                        Icons.Rounded.ViewAgenda,
                        stringResource(R.string.welcome_controls_title),
                        stringResource(R.string.welcome_controls_subtitle),
                    )
                    ReaderChromeStylePicker(
                        selected = settings.readerChromeStyle,
                        colors = colors,
                        hapticsEnabled = settings.haptics,
                        enabled = !isSaving,
                        onSelected = { if (!isSaving) onSettingsChange(settings.copy(readerChromeStyle = it)) },
                    )
                }
                WelcomeCard {
                    SettingsControlHeader(
                        Icons.AutoMirrored.Rounded.Login,
                        stringResource(R.string.settings_startup_title),
                        stringResource(R.string.welcome_startup_subtitle),
                    )
                    WelcomeStartupPicker(
                        selected = settings.startupDestination,
                        enabled = !isSaving,
                        onSelected = { onSettingsChange(settings.copy(startupDestination = it)) },
                    )
                }
                WelcomeCard {
                    SettingsControlHeader(
                        Icons.Rounded.Palette,
                        stringResource(R.string.welcome_theme_title),
                        stringResource(R.string.welcome_theme_subtitle),
                    )
                    ThemePreviewPicker(
                        settings = settings,
                        effectiveTheme = theme,
                        enabled = !isSaving,
                        onSelect = {
                            if (!isSaving) onSettingsChange(settings.copy(theme = it, followSystemTheme = false))
                        },
                    )
                }
                SettingsSectionLabel(stringResource(R.string.settings_theme_automatic))
                SettingsCard {
                    ThemeAutomaticControls(
                        settings = settings,
                        enabled = !isSaving,
                        onUpdate = { transform ->
                            if (!isSaving) onSettingsChange(transform(settings))
                        },
                    )
                }
                errorMessage?.let {
                    Text(
                        it,
                        color = scheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = onContinue,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        stringResource(if (isSaving) R.string.welcome_saving else R.string.welcome_continue),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeader() {
    val largeText = LocalDensity.current.fontScale >= 1.4f
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 144.dp)) {
            Column(
                Modifier.fillMaxWidth(if (largeText) 1f else .76f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 34.sp,
                        lineHeight = 39.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        stringResource(R.string.welcome_release_label),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            // Reserved for the future SVG. Leave it empty, without placeholder
            // artwork, borders or accessibility text. It can be replaced here.
            if (!largeText) {
                Box(
                    Modifier.align(Alignment.TopEnd).width(maxWidth * .24f).height(144.dp)
                        .clearAndSetSemantics { },
                )
            }
        }
        Text(
            stringResource(R.string.welcome_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun WelcomeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Flexible-height segments preserve both labels at large font sizes. */
@Composable
private fun WelcomeStartupPicker(
    selected: StartupDestination,
    enabled: Boolean,
    onSelected: (StartupDestination) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FrogSegmentedControl(
        count = StartupDestination.entries.size,
        selectedIndex = StartupDestination.entries.indexOf(selected),
        enabled = enabled,
        containerColor = colors.surfaceContainer,
        indicatorColor = colors.primary,
        contentColor = colors.onSurface,
        selectedContentColor = colors.onPrimary,
        gap = 4.dp,
        containerRadius = 28.dp,
        indicatorRadius = 24.dp,
        minItemHeight = 48.dp,
        onSelect = { onSelected(StartupDestination.entries[it]) },
    ) { index, _, foreground ->
        val destination = StartupDestination.entries[index]
        Text(
            stringResource(
                if (destination == StartupDestination.LIBRARY) R.string.nav_library
                else R.string.settings_startup_last_book,
            ),
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}
