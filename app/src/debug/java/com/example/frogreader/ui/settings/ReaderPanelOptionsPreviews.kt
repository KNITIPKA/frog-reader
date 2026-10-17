package com.example.frogreader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.frogreader.R
import com.example.frogreader.data.AppSettings
import com.example.frogreader.data.AppTheme
import com.example.frogreader.ui.theme.FrogReaderTheme

@Preview(widthDp = 393, heightDp = 440)
@Composable
private fun ReaderPanelOptionsLightPreview() = PanelOptionsPreview(AppTheme.WHITE)

@Preview(widthDp = 393, heightDp = 440)
@Composable
private fun ReaderPanelOptionsBeigePreview() = PanelOptionsPreview(AppTheme.SEPIA)

@Preview(widthDp = 393, heightDp = 440)
@Composable
private fun ReaderPanelOptionsMidnightPreview() = PanelOptionsPreview(AppTheme.OLED, enabled = true)

@Preview(widthDp = 320, heightDp = 650, fontScale = 1.5f)
@Composable
private fun ReaderPanelOptionsLargeTextPreview() = PanelOptionsPreview(AppTheme.WHITE)

@Composable
private fun PanelOptionsPreview(theme: AppTheme, enabled: Boolean = false) {
    var settings by remember {
        mutableStateOf(AppSettings(
            showCollapsedReaderHandle = enabled,
            expandReaderPanelsOnSwipe = enabled,
        ))
    }
    FrogReaderTheme(theme) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SettingsSectionLabel(stringResource(R.string.settings_reading_behavior))
            SettingsCard {
                ReaderPanelBehaviorSettingsRows(settings) { settings = it(settings) }
            }
        }
    }
}
