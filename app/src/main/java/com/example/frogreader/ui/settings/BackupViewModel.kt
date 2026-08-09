package com.example.frogreader.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.backup.BackupRef
import com.example.frogreader.data.backup.BackupRepository
import com.example.frogreader.data.backup.SafDocumentTarget
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives backing up and restoring from the settings screen.
 *
 * Holds the manifest of a file the user has chosen but not yet confirmed: a
 * restore replaces the whole library, so it takes two deliberate steps, and the
 * second one shows what is actually in the file rather than what the file is
 * called.
 */
class BackupViewModel(
    private val app: FrogReaderApp,
    private val backups: BackupRepository,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data class Working(val restoring: Boolean, val done: Int, val total: Int) : State
        data class ExportDone(val books: Int) : State
        data class RestoreDone(
            val books: Int,
            val quotes: Int,
            val booksWithoutFile: Int,
        ) : State

        data class Failed(val restoring: Boolean, val message: String) : State
    }

    /** A backup the user picked, waiting on confirmation. */
    data class Pending(val uri: Uri, val manifest: BackupManifest)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    fun suggestedFileName(): String = backups.suggestedFileName()

    fun export(uri: Uri, mode: BackupMode) {
        _state.value = State.Working(restoring = false, done = 0, total = 0)
        viewModelScope.launch {
            runCatching {
                backups.export(
                    target = SafDocumentTarget(app, uri),
                    mode = mode,
                    onProgress = { done, total ->
                        _state.value = State.Working(restoring = false, done = done, total = total)
                    },
                )
            }.onSuccess {
                _state.value = State.ExportDone(app.bookRepository.books.value.size)
            }.onFailure { error ->
                _state.value = State.Failed(restoring = false, message = error.readable())
            }
        }
    }

    /** Reads the header of a chosen file so the user can see what it holds. */
    fun inspect(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                backups.inspect(SafDocumentTarget(app, uri), BackupRef(uri.toString(), ""))
            }.onSuccess { manifest ->
                _pending.value = Pending(uri, manifest)
            }.onFailure { error ->
                _state.value = State.Failed(restoring = true, message = error.readable())
            }
        }
    }

    fun cancelPending() {
        _pending.value = null
    }

    fun confirmRestore() {
        val pending = _pending.value ?: return
        _pending.value = null
        _state.value = State.Working(restoring = true, done = 0, total = pending.manifest.bookCount)
        viewModelScope.launch {
            runCatching {
                backups.restore(
                    target = SafDocumentTarget(app, pending.uri),
                    ref = BackupRef(pending.uri.toString(), ""),
                    onProgress = { done, total ->
                        _state.value = State.Working(restoring = true, done = done, total = total)
                    },
                )
            }.onSuccess { summary ->
                _state.value = State.RestoreDone(
                    books = summary.books,
                    quotes = summary.quotes,
                    booksWithoutFile = summary.booksWithoutFile,
                )
            }.onFailure { error ->
                _state.value = State.Failed(restoring = true, message = error.readable())
            }
        }
    }

    fun dismissResult() {
        _state.value = State.Idle
    }

    private fun Throwable.readable(): String =
        message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "unknown error")

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                BackupViewModel(app, app.backupRepository)
            }
        }
    }
}
