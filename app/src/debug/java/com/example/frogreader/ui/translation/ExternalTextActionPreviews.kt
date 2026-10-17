package com.example.frogreader.ui.translation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppTheme
import com.example.frogreader.data.translation.EXTERNAL_TEXT_ASK_EVERY_TIME
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.data.translation.ExternalTextApp
import com.example.frogreader.data.translation.TextIntentMechanism
import com.example.frogreader.ui.settings.ExternalTextActionSettingsRow
import com.example.frogreader.ui.settings.SettingsCard
import com.example.frogreader.ui.settings.SettingsDivider
import com.example.frogreader.ui.theme.FrogReaderTheme
import com.example.frogreader.ui.theme.floatingSurface

@Preview(name = "Translate picker · Light", widthDp = 393, heightDp = 640)
@Composable
private fun TranslatePickerLightPreview() = PickerPreview(AppTheme.WHITE, ExternalTextAction.TRANSLATE)

@Preview(name = "Dictionary picker · Beige", widthDp = 393, heightDp = 640)
@Composable
private fun DictionaryPickerBeigePreview() = PickerPreview(AppTheme.SEPIA, ExternalTextAction.DEFINE)

@Preview(name = "Search picker · Midnight", widthDp = 393, heightDp = 640)
@Composable
private fun SearchPickerMidnightPreview() = PickerPreview(AppTheme.OLED, ExternalTextAction.SEARCH)

@Preview(name = "Translate picker · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun TranslatePickerLargeTextPreview() = PickerPreview(AppTheme.WHITE, ExternalTextAction.TRANSLATE)

@Preview(name = "Dictionary picker · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun DictionaryPickerLargeTextPreview() = PickerPreview(AppTheme.SEPIA, ExternalTextAction.DEFINE)

@Preview(name = "Search picker · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun SearchPickerLargeTextPreview() = PickerPreview(AppTheme.OLED, ExternalTextAction.SEARCH)

@Preview(name = "Dictionary picker · empty", widthDp = 393, heightDp = 560)
@Composable
private fun EmptyDictionaryPickerPreview() = PickerPreview(AppTheme.SEPIA, ExternalTextAction.DEFINE, emptyList())

@Preview(name = "Search picker · loading", widthDp = 393, heightDp = 560)
@Composable
private fun LoadingSearchPickerPreview() = PickerPreview(AppTheme.WHITE, ExternalTextAction.SEARCH, null)

@Preview(name = "External app settings · Light", widthDp = 393, heightDp = 640)
@Composable
private fun ExternalSettingsLightPreview() = SettingsPreview(AppTheme.WHITE)

@Preview(name = "External app settings · Beige", widthDp = 393, heightDp = 640)
@Composable
private fun ExternalSettingsBeigePreview() = SettingsPreview(AppTheme.SEPIA)

@Preview(name = "External app settings · Midnight", widthDp = 393, heightDp = 640)
@Composable
private fun ExternalSettingsMidnightPreview() = SettingsPreview(AppTheme.OLED)

@Preview(name = "External app settings · narrow large text", widthDp = 320, heightDp = 720, fontScale = 1.5f)
@Composable
private fun ExternalSettingsLargeTextPreview() = SettingsPreview(AppTheme.SEPIA)

/** In-memory fixtures exercise the production content without querying installed packages. */
private fun sampleApps(action: ExternalTextAction): List<ExternalTextApp> {
    val mechanism = when (action) {
        ExternalTextAction.TRANSLATE -> TextIntentMechanism.PROCESS_TEXT
        ExternalTextAction.DEFINE -> TextIntentMechanism.DEFINE
        ExternalTextAction.SEARCH -> TextIntentMechanism.WEB_SEARCH
    }
    return listOf(
        ExternalTextApp("sample/First", "Preferred app", "Selected text", mechanism, "sample/First"),
        ExternalTextApp("sample/Second", "Offline reference", "Look up words and phrases", mechanism, "sample/Second"),
        ExternalTextApp("sample/Third", "Another installed app", "Another installed app", mechanism, "sample/Third"),
    )
}

@Composable
private fun PickerPreview(
    theme: AppTheme,
    action: ExternalTextAction,
    apps: List<ExternalTextApp>? = sampleApps(action),
) {
    var selected by remember { mutableStateOf<String?>(apps?.firstOrNull()?.id) }
    FrogReaderTheme(theme) {
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.floatingSurface,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(action.titleRes), style = MaterialTheme.typography.headlineSmall)
                    ExternalTextAppPickerContent(
                        action = action,
                        apps = apps,
                        selected = selected,
                        onChoose = { selected = it.id },
                        onAskEveryTime = { selected = EXTERNAL_TEXT_ASK_EVERY_TIME },
                        appIcon = { Spacer(Modifier.size(32.dp)) },
                    )
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (selected != null) {
                            TextButton(onClick = { selected = null }) { Text(stringResource(R.string.translator_reset)) }
                        }
                        TextButton(onClick = {}) { Text(stringResource(R.string.settings_close)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPreview(theme: AppTheme) {
    FrogReaderTheme(theme) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SettingsCard {
                ExternalTextAction.entries.forEachIndexed { index, action ->
                    if (index > 0) SettingsDivider()
                    ExternalTextActionSettingsRow(
                        action = action,
                        selectedLabel = stringResource(
                            when (action) {
                                ExternalTextAction.TRANSLATE -> R.string.external_text_ask_every_time
                                ExternalTextAction.DEFINE -> R.string.external_text_not_selected
                                ExternalTextAction.SEARCH -> R.string.external_text_unavailable
                            },
                        ),
                        onClick = {},
                    )
                }
            }
        }
    }
}
