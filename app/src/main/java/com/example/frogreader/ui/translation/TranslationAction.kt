package com.example.frogreader.ui.translation

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.frogreader.R
import com.example.frogreader.data.translation.TranslationCoordinator
import com.example.frogreader.data.translation.queryTranslatorApps
import com.example.frogreader.data.translation.translationIntent
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun rememberTranslationAction(
    defaultTranslator: String?,
    saveDefault: suspend (String) -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferred by rememberUpdatedState(defaultTranslator)
    val save by rememberUpdatedState(saveDefault)
    // PROCESS_TEXT activities can return a result; the source book is read-only.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val coordinator = remember(context, launcher) {
        TranslationCoordinator(
            apps = { withContext(Dispatchers.IO) { queryTranslatorApps(context) } },
            saveDefault = { save(it) },
            launch = { app, text -> launcher.launch(translationIntent(app, text)) },
        )
    }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pickerRevision by remember { mutableIntStateOf(0) }

    fun request(text: String, choice: String? = null) {
        if (busy || text.isBlank()) return
        busy = true
        pendingText = text
        scope.launch {
            try {
                val launched = if (choice == null) coordinator.translate(text, preferred)
                    else coordinator.choose(text, choice)
                choosing = !launched
                if (launched) pendingText = null else pickerRevision++
            } catch (_: ActivityNotFoundException) {
                choosing = true
                pickerRevision++
                Toast.makeText(context, R.string.translator_launch_failed, Toast.LENGTH_LONG).show()
            } catch (_: SecurityException) {
                choosing = true
                pickerRevision++
                Toast.makeText(context, R.string.translator_launch_failed, Toast.LENGTH_LONG).show()
            } catch (_: IOException) {
                choosing = true
                Toast.makeText(context, R.string.translator_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    if (choosing) {
        key(pickerRevision) {
            TranslatorPickerDialog(
                selected = preferred,
                busy = busy,
                onChoose = { app -> pendingText?.let { request(it, app.id) } },
                onDismiss = { choosing = false; pendingText = null },
            )
        }
    }
    return { text -> request(text) }
}
