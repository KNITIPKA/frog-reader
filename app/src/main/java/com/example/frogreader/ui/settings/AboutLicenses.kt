package com.example.frogreader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.frogreader.R
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class AboutLicensePage { APP, THIRD_PARTY }

/** Packaged texts remain available without a network connection. */
@Composable
internal fun AboutLicensesDialog(initialPage: AboutLicensePage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    val unavailable = stringResource(R.string.settings_about_license_failed)
    val paragraphs by produceState<List<String>>(emptyList(), page, context) {
        value = emptyList()
        value = withContext(Dispatchers.IO) {
            runCatching {
                val files = if (page == AboutLicensePage.APP) {
                    listOf("gpl-3.0.txt")
                } else {
                    listOf("third-party-notices.txt", "Apache-2.0.txt", "jsoup-MIT.txt", "brotli-MIT.txt",
                        "commonmark-BSD.txt", "literata-OFL.txt", "ibm-plex-OFL.txt", "tabler-icons-MIT.txt")
                }
                files.flatMap { file ->
                    context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                        .split(Regex("\\n\\s*\\n"))
                }
            }.getOrDefault(listOf(unavailable))
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.floatingSurface) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.settings_about_licenses),
                        modifier = Modifier.weight(1f).padding(top = 14.dp),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.settings_close))
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { page = AboutLicensePage.APP }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_about_license_app))
                    }
                    TextButton(onClick = { page = AboutLicensePage.THIRD_PARTY }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_about_license_third_party))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page == AboutLicensePage.APP) {
                        item {
                            Text(stringResource(R.string.settings_about_license_notice), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(paragraphs.size) { index ->
                        Text(paragraphs[index], style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
