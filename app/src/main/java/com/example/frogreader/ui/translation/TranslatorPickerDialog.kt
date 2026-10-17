package com.example.frogreader.ui.translation

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.example.frogreader.R
import com.example.frogreader.data.translation.EXTERNAL_TEXT_ASK_EVERY_TIME
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.data.translation.ExternalTextApp
import com.example.frogreader.data.translation.queryExternalTextApps
import com.example.frogreader.data.translation.resolveExternalTextApp
import com.example.frogreader.ui.theme.floatingSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@get:StringRes
val ExternalTextAction.titleRes: Int
    get() = when (this) {
        ExternalTextAction.TRANSLATE -> R.string.settings_default_translator
        ExternalTextAction.DEFINE -> R.string.settings_default_dictionary
        ExternalTextAction.SEARCH -> R.string.settings_default_search_app
    }

@get:StringRes
val ExternalTextAction.descriptionRes: Int
    get() = when (this) {
        ExternalTextAction.TRANSLATE -> R.string.external_text_translate_description
        ExternalTextAction.DEFINE -> R.string.external_text_define_description
        ExternalTextAction.SEARCH -> R.string.external_text_search_description
    }

@get:StringRes
val ExternalTextAction.emptyRes: Int
    get() = when (this) {
        ExternalTextAction.TRANSLATE -> R.string.external_text_translate_empty
        ExternalTextAction.DEFINE -> R.string.external_text_define_empty
        ExternalTextAction.SEARCH -> R.string.external_text_search_empty
    }

/** Refresh on return from another app and while a package changes with this screen open. */
@Composable
fun rememberExternalTextApps(action: ExternalTextAction): List<ExternalTextApp>? {
    val context = LocalContext.current.applicationContext
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var revision by remember(context) { mutableIntStateOf(0) }
    var apps by remember(context, action) { mutableStateOf<List<ExternalTextApp>?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                revision++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // These are system package broadcasts; RECEIVER_EXPORTED also receives them on API 33+.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(context, action, lifecycle, revision) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            apps = withContext(Dispatchers.IO) {
                try {
                    queryExternalTextApps(context, action)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    // Package information can disappear during uninstall or replacement.
                    emptyList()
                }
            }
        }
    }
    return apps
}

@Composable
fun ExternalTextAppPickerDialog(
    action: ExternalTextAction,
    selected: String?,
    onChoose: (ExternalTextApp) -> Unit,
    onDismiss: () -> Unit,
    busy: Boolean = false,
    onReset: (() -> Unit)? = null,
    onAskEveryTime: (() -> Unit)? = null,
) {
    val apps = rememberExternalTextApps(action)
    AlertDialog(
        containerColor = MaterialTheme.colorScheme.floatingSurface,
        tonalElevation = 0.dp,
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(action.titleRes)) },
        text = {
            ExternalTextAppPickerContent(action, apps, selected, onChoose, busy, onAskEveryTime)
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.settings_close)) }
        },
        dismissButton = {
            if (onReset != null && selected != null) {
                TextButton(onClick = onReset, enabled = !busy) {
                    Text(stringResource(R.string.translator_reset))
                }
            }
        },
    )
}

/** The body scrolls as one unit so descriptions do not crowd choices at large text sizes. */
@Composable
internal fun ExternalTextAppPickerContent(
    action: ExternalTextAction,
    apps: List<ExternalTextApp>?,
    selected: String?,
    onChoose: (ExternalTextApp) -> Unit,
    busy: Boolean = false,
    onAskEveryTime: (() -> Unit)? = null,
    appIcon: @Composable (ExternalTextApp) -> Unit = { ExternalTextAppIcon(it) },
) {
    LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
        item(key = "description") {
            Text(stringResource(action.descriptionRes), Modifier.padding(bottom = 12.dp))
        }
        if (action == ExternalTextAction.TRANSLATE) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.external_text_process_text_hint),
                    modifier = Modifier.padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (apps == null || busy) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            if (onAskEveryTime != null) {
                item(key = EXTERNAL_TEXT_ASK_EVERY_TIME) {
                    ExternalTextChoiceRow(
                        label = stringResource(R.string.external_text_ask_every_time),
                        selected = selected == EXTERNAL_TEXT_ASK_EVERY_TIME,
                        onClick = onAskEveryTime,
                    )
                }
            }
            if (apps.isEmpty()) {
                item(key = "empty") {
                    Text(stringResource(action.emptyRes), Modifier.padding(vertical = 12.dp))
                }
            } else {
                items(apps, key = { it.id }) { app ->
                    val selectedApp = resolveExternalTextApp(listOf(app), selected)
                    ExternalTextChoiceRow(
                        label = app.label,
                        actionLabel = app.actionLabel.takeIf { it != app.label },
                        selected = selectedApp != null,
                        onClick = { onChoose(selectedApp ?: app) },
                        icon = { appIcon(app) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExternalTextChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    actionLabel: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        icon?.invoke()
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (actionLabel != null) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExternalTextAppIcon(app: ExternalTextApp) {
    val manager = LocalContext.current.packageManager
    val pixels = with(LocalDensity.current) { 32.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, manager, app.component, pixels) {
        value = withContext(Dispatchers.IO) {
            try {
                val component = requireNotNull(ComponentName.unflattenFromString(app.component))
                manager.getActivityIcon(component).toBitmap(pixels, pixels).asImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Spacer(Modifier.size(32.dp))
    }
}
