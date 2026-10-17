package com.example.frogreader.ui.translation

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import com.example.frogreader.R
import com.example.frogreader.data.translation.EXTERNAL_TEXT_ASK_EVERY_TIME
import com.example.frogreader.data.translation.ExternalTextAction
import com.example.frogreader.data.translation.ExternalTextCoordinator
import com.example.frogreader.data.translation.canSendExternalText
import com.example.frogreader.data.translation.externalTextIntent
import com.example.frogreader.data.translation.queryExternalTextApps
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

private class ExternalTextLaunchFailure(cause: RuntimeException) : RuntimeException(cause)

@Composable
fun rememberExternalTextAction(
    action: ExternalTextAction,
    preferredApp: String?,
    saveDefault: suspend (String) -> Unit,
    snackbar: SnackbarHostState,
    requestGate: Mutex,
    onLaunched: () -> Unit,
): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val preferred by rememberUpdatedState(preferredApp)
    val save by rememberUpdatedState(saveDefault)
    val launchedCallback by rememberUpdatedState(onLaunched)
    val launchFailedMessage by rememberUpdatedState(stringResource(R.string.external_text_launch_failed))
    val saveFailedMessage by rememberUpdatedState(stringResource(R.string.external_text_save_failed))
    val tooLongMessage by rememberUpdatedState(stringResource(R.string.external_text_selection_too_long))
    // All handoffs are read-only. No activity result is needed; the destination owns its task.
    val coordinator = remember(context, action) {
        ExternalTextCoordinator(
            apps = { withContext(Dispatchers.IO) { queryExternalTextApps(context, action) } },
            saveDefault = { save(it) },
            launch = { app, text ->
                try {
                    context.startActivity(externalTextIntent(app, text))
                } catch (failure: RuntimeException) {
                    // Includes ActivityNotFoundException, SecurityException, and Binder failures.
                    throw ExternalTextLaunchFailure(failure)
                }
            },
        )
    }
    var pendingText by remember(action) { mutableStateOf<String?>(null) }
    var choosing by remember(action) { mutableStateOf(false) }
    var busy by remember(action) { mutableStateOf(false) }
    var askForThisRequest by remember(action) { mutableStateOf(false) }
    var pickerRevision by remember(action) { mutableIntStateOf(0) }

    fun showFailure(message: String) {
        choosing = true
        pickerRevision++
        scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Long) }
    }

    fun request(text: String, choice: String? = null) {
        if (busy || text.isBlank()) return
        if (!canSendExternalText(text)) {
            scope.launch { snackbar.showSnackbar(tooLongMessage, duration = SnackbarDuration.Long) }
            return
        }
        scope.launch {
            // All three reader actions share this gate: rapid taps cannot hand off twice while
            // another role is still resolving its target on the IO dispatcher.
            if (!requestGate.tryLock()) return@launch
            busy = true
            pendingText = text
            if (choice == null) askForThisRequest = preferred == EXTERNAL_TEXT_ASK_EVERY_TIME
            try {
                val launched = if (choice == null) coordinator.perform(text, preferred)
                    else coordinator.choose(text, choice, rememberChoice = !askForThisRequest)
                choosing = !launched
                if (launched) {
                    pendingText = null
                    launchedCallback()
                } else pickerRevision++
            } catch (_: ExternalTextLaunchFailure) {
                showFailure(launchFailedMessage)
            } catch (_: IOException) {
                showFailure(saveFailedMessage)
            } catch (failure: RuntimeException) {
                if (failure is CancellationException) throw failure
                showFailure(saveFailedMessage)
            } finally {
                busy = false
                requestGate.unlock()
            }
        }
    }

    if (choosing) {
        key(pickerRevision) {
            ExternalTextAppPickerDialog(
                action = action,
                selected = if (askForThisRequest) EXTERNAL_TEXT_ASK_EVERY_TIME else preferred,
                busy = busy,
                onChoose = { app -> pendingText?.let { request(it, app.id) } },
                onAskEveryTime = {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                save(EXTERNAL_TEXT_ASK_EVERY_TIME)
                                askForThisRequest = true
                            } catch (_: IOException) {
                                showFailure(saveFailedMessage)
                            } catch (failure: RuntimeException) {
                                if (failure is CancellationException) throw failure
                                showFailure(saveFailedMessage)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                // Retain the reader selection on dismissal/failure; only a successful handoff
                // clears it. A pending query is never silently truncated or sent elsewhere.
                onDismiss = { choosing = false; pendingText = null },
            )
        }
    }
    return { text -> request(text) }
}
