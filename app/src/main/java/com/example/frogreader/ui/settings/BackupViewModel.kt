package com.example.frogreader.ui.settings

import android.net.Uri
import android.content.Intent
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.backup.ScheduledBackupWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.core.net.toUri
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.backup.BackupRef
import com.example.frogreader.data.backup.BackupRepository
import com.example.frogreader.data.backup.SafDocumentTarget
import com.example.frogreader.data.backup.SafFolderTarget
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    data class Pending(
        val uri: Uri,
        val manifest: BackupManifest,
        /** Non-null when this came from a retained-folder row. */
        val sourceFolderUri: String? = null,
    )

    /** Current library totals used by the backup overview. */
    data class LibrarySummary(
        val bookCount: Int = 0,
        val quoteCount: Int = 0,
        val totalBookBytes: Long = 0L,
        val totalCoverBytes: Long = 0L,
        /** Source bytes, not the final compressed ZIP size. */
        val fullBackupEstimatedBytes: Long = totalBookBytes + totalCoverBytes,
        val fullBackupEstimateApproximate: Boolean = true,
    )

    /** A file in the selected backup folder, with a manifest when it is readable. */
    data class Snapshot(
        val sourceFolderUri: String,
        val ref: BackupRef,
        val manifest: BackupManifest?,
    )

    private fun summarizeLibrary(books: List<Book>): LibrarySummary {
        // A restored data-only record may retain its historical size while its
        // fileName is null. Stat only files the FULL exporter can actually open.
        val bookBytes = books.sumOf { book ->
            app.bookRepository.bookFileFor(book)?.length()?.coerceAtLeast(0L) ?: 0L
        }
        val coverBytes = books.sumOf { book ->
            app.bookRepository.coverFileFor(book)?.length()?.coerceAtLeast(0L) ?: 0L
        }
        return LibrarySummary(
            bookCount = books.size,
            quoteCount = books.sumOf { it.quotes.size },
            totalBookBytes = bookBytes,
            totalCoverBytes = coverBytes,
            fullBackupEstimatedBytes = bookBytes + coverBytes,
            // ZIP compression and the small JSON payload make an exact output
            // byte count unknowable without doing the export itself.
            fullBackupEstimateApproximate = true,
        )
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()

    private val operationMutex = Mutex()

    val librarySummary: StateFlow<LibrarySummary> = app.bookRepository.books
        .map(::summarizeLibrary)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = summarizeLibrary(app.bookRepository.books.value),
        )

    private val _snapshots = MutableStateFlow<List<Snapshot>>(emptyList())
    val snapshots: StateFlow<List<Snapshot>> = _snapshots.asStateFlow()

    private val _snapshotsLoading = MutableStateFlow(false)
    val snapshotsLoading: StateFlow<Boolean> = _snapshotsLoading.asStateFlow()

    private val _snapshotsError = MutableStateFlow<String?>(null)
    val snapshotsError: StateFlow<String?> = _snapshotsError.asStateFlow()

    private val _snapshotsFolderUri = MutableStateFlow<String?>(null)
    val snapshotsFolderUri: StateFlow<String?> = _snapshotsFolderUri.asStateFlow()

    private val _folderChanging = MutableStateFlow(false)
    val folderChanging: StateFlow<Boolean> = _folderChanging.asStateFlow()

    val folder: StateFlow<String?> = app.settingsRepository.backupFolder
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val frequency: StateFlow<BackupFrequency> = app.settingsRepository.backupFrequency
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupFrequency.OFF)

    val lastBackupAt: StateFlow<Long?> = app.settingsRepository.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var snapshotRefreshJob: Job? = null
    private var snapshotRefreshVersion = 0L
    private var folderChangeJob: Job? = null
    private var folderChangeVersion = 0L

    init {
        // A scheduled backup runs outside this ViewModel. Observing its timestamp
        // keeps the folder history current when the worker finishes while the
        // settings screen is open, as well as on initial load and folder changes.
        viewModelScope.launch {
            combine(folder, lastBackupAt) { folderUri, recordedAt -> folderUri to recordedAt }
                .collect { (folderUri, _) -> refreshSnapshots(folderUri) }
        }
    }

    fun suggestedFileName(): String = backups.suggestedFileName()

    /**
     * Remembers the folder scheduled backups go to.
     *
     * The permission has to be taken persistably here: without it the grant
     * dies with the process, and the job would start failing silently at some
     * point after the user stopped watching.
     */
    fun setFolder(uri: Uri) {
        if (_operationInProgress.value) return

        val previousFolderUri = folder.value
        val changeVersion = ++folderChangeVersion
        folderChangeJob?.cancel()
        snapshotRefreshJob?.cancel()
        snapshotRefreshVersion++
        _snapshotsFolderUri.value = null
        _snapshots.value = emptyList()
        _snapshotsLoading.value = false
        _snapshotsError.value = null
        if (_pending.value?.sourceFolderUri != null) _pending.value = null
        _folderChanging.value = true

        val permissionFailure = runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.exceptionOrNull()
        if (permissionFailure != null) {
            _state.value = State.Failed(
                restoring = false,
                message = permissionFailure.readable(),
            )
            if (changeVersion == folderChangeVersion) {
                _folderChanging.value = false
                folderChangeJob = null
                refreshSnapshots(previousFolderUri)
            }
            return
        }

        folderChangeJob = viewModelScope.launch {
            var folderPersisted = false
            try {
                // Verify the persisted grant and write capability before replacing
                // a previously working folder with an unusable URI.
                SafFolderTarget(app, uri).list()
                if (changeVersion != folderChangeVersion) return@launch
                app.settingsRepository.setBackupFolder(uri.toString())
                folderPersisted = true
                if (changeVersion != folderChangeVersion) return@launch
                ScheduledBackupWorker.apply(app, app.settingsRepository.backupFrequency.first())
                refreshSnapshots(uri.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (changeVersion == folderChangeVersion) {
                    _state.value = State.Failed(restoring = false, message = error.readable())
                    refreshSnapshots(
                        if (folderPersisted) uri.toString() else previousFolderUri,
                    )
                }
            } finally {
                if (changeVersion == folderChangeVersion) {
                    _folderChanging.value = false
                    folderChangeJob = null
                }
            }
        }
    }

    fun setFrequency(value: BackupFrequency) {
        if (_operationInProgress.value || _folderChanging.value) return
        viewModelScope.launch {
            app.settingsRepository.setBackupFrequency(value)
            ScheduledBackupWorker.apply(app, value)
        }
    }

    fun export(uri: Uri, mode: BackupMode) {
        launchExclusiveOperation {
            _state.value = State.Working(restoring = false, done = 0, total = 0)
            try {
                backups.export(
                    target = SafDocumentTarget(app, uri),
                    mode = mode,
                    onProgress = { done, total ->
                        _state.value = State.Working(restoring = false, done = done, total = total)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = State.Failed(restoring = false, message = error.readable())
                return@launchExclusiveOperation
            }

            recordSuccessfulBackupAtBestEffort()
            _state.value = State.ExportDone(librarySummary.value.bookCount)
        }
    }

    /** Writes a backup directly into the folder selected for automatic backups. */
    fun exportToFolder(mode: BackupMode) {
        val folderUri = folder.value
        if (folderUri == null) {
            _state.value = State.Failed(
                restoring = false,
                message = "Choose a backup folder first",
            )
            return
        }

        launchExclusiveOperation {
            _state.value = State.Working(restoring = false, done = 0, total = 0)
            val target = SafFolderTarget(app, folderUri.toUri())
            try {
                backups.exportToFolder(
                    target = target,
                    mode = mode,
                    fileName = backups.suggestedManualSnapshotFileName(),
                    keep = BackupRepository.DEFAULT_KEEP,
                    onProgress = { done, total ->
                        _state.value = State.Working(restoring = false, done = done, total = total)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = State.Failed(restoring = false, message = error.readable())
                return@launchExclusiveOperation
            }

            recordSuccessfulBackupAtBestEffort()
            _state.value = State.ExportDone(librarySummary.value.bookCount)
            refreshSnapshots(folderUri)
        }
    }

    /** Reads the header of a chosen file so the user can see what it holds. */
    fun inspect(uri: Uri) {
        inspect(uri, sourceFolderUri = null)
    }

    private fun inspect(uri: Uri, sourceFolderUri: String?) {
        if (
            sourceFolderUri != null &&
            (sourceFolderUri != folder.value || sourceFolderUri != _snapshotsFolderUri.value)
        ) return
        launchExclusiveOperation {
            try {
                val manifest = backups.inspect(
                    SafDocumentTarget(app, uri),
                    BackupRef(uri.toString(), ""),
                )
                if (
                    sourceFolderUri == null ||
                    (sourceFolderUri == folder.value && sourceFolderUri == _snapshotsFolderUri.value)
                ) {
                    _pending.value = Pending(uri, manifest, sourceFolderUri)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = State.Failed(restoring = true, message = error.readable())
            }
        }
    }

    /** Re-inspects a listed snapshot so confirmation always describes its current contents. */
    fun inspect(snapshot: Snapshot) {
        inspect(snapshot.ref.id.toUri(), snapshot.sourceFolderUri)
    }

    fun cancelPending() {
        _pending.value = null
    }

    fun confirmRestore() {
        val pending = _pending.value ?: return
        launchExclusiveOperation {
            // Clear only after the operation lock is ours. A rejected rapid tap
            // must leave the already inspected backup available to confirm.
            if (_pending.value != pending) return@launchExclusiveOperation
            _pending.value = null
            _state.value = State.Working(restoring = true, done = 0, total = pending.manifest.bookCount)
            val summary = try {
                backups.restore(
                    target = SafDocumentTarget(app, pending.uri),
                    ref = BackupRef(pending.uri.toString(), ""),
                    onProgress = { done, total ->
                        _state.value = State.Working(restoring = true, done = done, total = total)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = State.Failed(restoring = true, message = error.readable())
                return@launchExclusiveOperation
            }

            _state.value = State.RestoreDone(
                books = summary.books,
                quotes = summary.quotes,
                booksWithoutFile = summary.booksWithoutFile,
            )
            refreshSnapshots()
        }
    }

    /** Reloads the newest retained backups from the currently selected folder. */
    fun refreshSnapshots() {
        refreshSnapshots(folder.value)
    }

    fun dismissResult() {
        _state.value = State.Idle
    }

    /**
     * Backup I/O is deliberately single-flight. There is no pretend cancel:
     * later taps are ignored until the operation that owns the archive or the
     * library has completed, so export and destructive restore cannot overlap.
     */
    private fun launchExclusiveOperation(block: suspend () -> Unit) {
        if (_folderChanging.value || !operationMutex.tryLock()) return
        _operationInProgress.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _operationInProgress.value = false
                operationMutex.unlock()
            }
        }
    }

    /** A written ZIP stays a success even if the summary timestamp cannot persist. */
    private suspend fun recordSuccessfulBackupAtBestEffort() {
        try {
            app.settingsRepository.recordBackupAt(System.currentTimeMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The archive is already safely in the user's destination. Reporting
            // it as failed would encourage a duplicate while losing the truth.
        }
    }

    private fun Throwable.readable(): String =
        message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "unknown error")

    private fun refreshSnapshots(folderUri: String?) {
        val refreshVersion = ++snapshotRefreshVersion
        snapshotRefreshJob?.cancel()
        if (_snapshotsFolderUri.value != folderUri) {
            // Never leave rows from folder A actionable under folder B while B
            // is still being enumerated.
            _snapshotsFolderUri.value = folderUri
            _snapshots.value = emptyList()
            if (_pending.value?.sourceFolderUri != folderUri) {
                _pending.value = null
            }
        }
        if (folderUri == null) {
            _snapshots.value = emptyList()
            _snapshotsLoading.value = false
            _snapshotsError.value = null
            snapshotRefreshJob = null
            return
        }

        snapshotRefreshJob = viewModelScope.launch {
            _snapshotsLoading.value = true
            _snapshotsError.value = null
            try {
                val target = SafFolderTarget(app, folderUri.toUri())
                val loaded = backups.listStoredBackups(
                    target = target,
                    limit = BackupRepository.DEFAULT_KEEP,
                ).map { stored ->
                    Snapshot(
                        sourceFolderUri = folderUri,
                        ref = stored.ref,
                        manifest = stored.manifest,
                    )
                }
                if (
                    refreshVersion == snapshotRefreshVersion &&
                    _snapshotsFolderUri.value == folderUri
                ) {
                    _snapshots.value = loaded
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (
                    refreshVersion == snapshotRefreshVersion &&
                    _snapshotsFolderUri.value == folderUri
                ) {
                    _snapshots.value = emptyList()
                    _snapshotsError.value = error.readable()
                }
            } finally {
                if (refreshVersion == snapshotRefreshVersion) {
                    _snapshotsLoading.value = false
                }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as FrogReaderApp
                BackupViewModel(app, app.backupRepository)
            }
        }
    }
}
