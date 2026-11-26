package com.example.frogreader.data.backup

import android.content.Context
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backing the library up (`VACUUM INTO` SQLite snapshot + settings + fonts + covers/books)
 * and restoring it safely via staging, integrity checks, and `.pre_restore_bak` rollback.
 */
class BackupRepository(
    private val context: Context,
    private val books: BookRepository,
    private val stats: StatsRepository,
    private val settings: SettingsRepository,
) {

    /** What a restore actually did, for the message shown afterwards. */
    data class RestoreSummary(
        val books: Int,
        val quotes: Int,
        val booksWithoutFile: Int,
        val readingQueueCount: Int = 0,
        val readingLogCount: Int = 0,
    )

    /** A restorable FrogReader archive retained in a folder target. */
    data class StoredBackup(
        val ref: BackupRef,
        val manifest: BackupManifest,
    )

    private val filesRoot get() = books.filesRoot
    private val booksDir get() = File(filesRoot, "books")
    private val coversDir get() = File(filesRoot, "covers")
    private val fontsDir get() = File(filesRoot, "fonts")
    private val operationMutex = Mutex()

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        return "frogreader-$stamp.zip"
    }

    /**
     * A collision-resistant name for a backup the user explicitly writes into
     * the retained snapshots folder.
     */
    fun suggestedManualSnapshotFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss-SSS", Locale.US).format(Date(now))
        return "frogreader-$stamp.zip"
    }

    suspend fun export(
        target: BackupTarget,
        mode: BackupMode,
        fileName: String = suggestedFileName(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupRef = operationMutex.withLock {
        exportUnlocked(target, mode, fileName, onProgress)
    }

    /**
     * Writes and rotates one folder snapshot as a single repository operation.
     */
    suspend fun exportToFolder(
        target: BackupTarget,
        mode: BackupMode,
        fileName: String = suggestedFileName(),
        keep: Int = DEFAULT_KEEP,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupRef = operationMutex.withLock {
        val written = exportUnlocked(target, mode, fileName, onProgress)
        try {
            rotateStoredBackupsUnlocked(target, keep)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The requested archive is already complete. Retention can recover on the next run.
        }
        written
    }

    /** Newest valid FrogReader snapshots, excluding unrelated or corrupt ZIPs. */
    suspend fun listStoredBackups(
        target: BackupTarget,
        limit: Int = DEFAULT_KEEP,
    ): List<StoredBackup> = operationMutex.withLock {
        validStoredBackupsUnlocked(target, stopAfter = limit.coerceAtLeast(0))
    }

    private suspend fun exportUnlocked(
        target: BackupTarget,
        mode: BackupMode,
        fileName: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BackupRef = withContext(Dispatchers.IO) {
        if (mode == BackupMode.FULL) books.flushPdfAnnotations()
        books.withBackupLock {
            val backupSettings = settings.snapshotForBackup()

            target.write(fileName) { out ->
                BackupArchive.write(
                    out = out,
                    filesRoot = filesRoot,
                    settings = backupSettings,
                    mode = mode,
                    appVersion = appVersion(),
                    createdAtMillis = System.currentTimeMillis(),
                    booksDir = booksDir,
                    coversDir = coversDir,
                    fontsDir = fontsDir,
                    onProgress = onProgress,
                )
            }
        }
    }

    /** Reads the header only, to show the user what they are about to restore. */
    suspend fun inspect(target: BackupTarget, ref: BackupRef): BackupManifest =
        operationMutex.withLock {
            inspectUnlocked(target, ref)
        }

    private suspend fun inspectUnlocked(target: BackupTarget, ref: BackupRef): BackupManifest =
        withContext(Dispatchers.IO) {
            target.open(ref).use(BackupArchive::readManifest)
        }

    /**
     * Replaces the library, stats, custom fonts, and settings with the contents of a validated
     * SQLite backup archive.
     */
    suspend fun restore(
        target: BackupTarget,
        ref: BackupRef,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RestoreSummary = operationMutex.withLock {
        restoreUnlocked(target, ref, onProgress)
    }

    private suspend fun restoreUnlocked(
        target: BackupTarget,
        ref: BackupRef,
        onProgress: (done: Int, total: Int) -> Unit,
    ): RestoreSummary = withContext(Dispatchers.IO) {
        val restored = books.withRestoreLock {
            stats.withRestoreLock {
                settings.prepareRestoreSettings()
                try {
                    val result = target.open(ref).use { input ->
                        BackupArchive.restore(
                            input = input,
                            targetFilesRoot = filesRoot,
                            booksDir = booksDir,
                            coversDir = coversDir,
                            fontsDir = fontsDir,
                            reconcileFiles = true,
                            onProgress = onProgress,
                            beforeCommit = { fromBackup ->
                                fromBackup?.let { settings.applyBackupSettings(it) }
                            },
                        )
                    }
                    settings.finishRestoreSettings()
                    result
                } catch (failure: Throwable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            settings.rollbackRestoreSettings()
                        } catch (settingsFailure: Throwable) {
                            failure.addSuppressed(settingsFailure)
                        }
                    }
                    throw failure
                }
            }
        }

        RestoreSummary(
            books = restored.counts.books,
            quotes = restored.counts.quotes,
            booksWithoutFile = restored.counts.booksWithoutFile,
            readingQueueCount = restored.counts.readingQueueCount,
            readingLogCount = restored.counts.readingLogCount,
        )
    }

    private suspend fun validStoredBackupsUnlocked(
        target: BackupTarget,
        stopAfter: Int? = null,
    ): List<StoredBackup> {
        if (stopAfter == 0) return emptyList()
        val result = mutableListOf<StoredBackup>()
        val candidates = target.list()
            .asSequence()
            .filter { it.name.isFrogReaderSnapshotFileName() }
            .sortedByDescending { it.modifiedAtMillis }
        for (ref in candidates) {
            val manifest = try {
                inspectUnlocked(target, ref)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            result += StoredBackup(ref, manifest)
            if (stopAfter != null && result.size >= stopAfter) break
        }
        return result
    }

    private suspend fun rotateStoredBackupsUnlocked(target: BackupTarget, keep: Int) {
        validStoredBackupsUnlocked(target)
            .drop(keep.coerceAtLeast(1))
            .forEach { stored ->
                try {
                    target.delete(stored.ref)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best-effort rotation.
                }
            }
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    companion object {
        const val DEFAULT_KEEP = DEFAULT_BACKUPS_KEPT
    }
}
