package com.example.frogreader.data.backup

import android.content.Context
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.SettingsRepository
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.model.BackupDocument
import com.example.frogreader.data.model.BackupManifest
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backing the library up and putting it back.
 *
 * Wires [BackupArchive] (which knows the format) to the repositories (which
 * know the data) and to a [BackupTarget] (which knows where the file goes).
 * None of the three knows about the other two, which is why a Google Drive
 * target could be added later without touching the format or the screens.
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
    )

    private val booksDir get() = File(context.filesDir, "books")
    private val coversDir get() = File(context.filesDir, "covers")

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        return "frogreader-$stamp.zip"
    }

    suspend fun export(
        target: BackupTarget,
        mode: BackupMode,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BackupRef = withContext(Dispatchers.IO) {
        val document = BackupDocument(
            books = books.books.value,
            shelves = books.shelves.value,
        )
        val backupSettings = BackupSettings(
            reader = settings.settings.first(),
            app = settings.appSettings.first(),
        )
        val currentStats = stats.stats.value

        target.write(suggestedFileName()) { out ->
            BackupArchive.write(
                out = out,
                document = document,
                stats = currentStats,
                settings = backupSettings,
                mode = mode,
                appVersion = appVersion(),
                createdAtMillis = System.currentTimeMillis(),
                bookFile = { books.bookFileFor(it) },
                coverFile = { books.coverFileFor(it) },
                onProgress = onProgress,
            )
        }
    }

    /** Reads the header only, to show the user what they are about to restore. */
    suspend fun inspect(target: BackupTarget, ref: BackupRef): BackupManifest =
        withContext(Dispatchers.IO) {
            target.open(ref).use { BackupArchive.readManifest(it) }
        }

    /**
     * Replaces the library with the contents of a backup.
     *
     * Replacement, not merge: the user is saying "make it be this". Merging by
     * id would be kinder to a device that already has books, and the manifest
     * carries a format version so it can be added later without invalidating
     * anything already written — but guessing which of two versions of a quote
     * is the right one is a decision this has no basis to make yet.
     *
     * Books whose file is not in the archive — every book in a DATA backup —
     * come back as records with no file. Everything written about them is
     * intact, and attaching a file later restores them completely.
     */
    suspend fun restore(
        target: BackupTarget,
        ref: BackupRef,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RestoreSummary = withContext(Dispatchers.IO) {
        val contents = target.open(ref).use { input ->
            BackupArchive.read(
                input = input,
                booksDir = booksDir,
                coversDir = coversDir,
                onProgress = onProgress,
            )
        }

        val restored = contents.document.books.map { book ->
            val fileName = contents.restoredBookFiles[book.id]
            book.copy(
                // A DATA backup carries no files, and a FULL one can still be
                // missing the odd book. Either way the record survives; only
                // the pointer to the file goes.
                fileName = fileName ?: book.fileName?.takeIf { File(booksDir, it).exists() },
                coverFileName = book.coverFileName?.takeIf { File(coversDir, it).exists() },
            )
        }

        books.replaceAll(restored, contents.document.shelves)
        contents.stats?.let { stats.replaceAll(it) }
        contents.settings?.let { fromBackup ->
            settings.update { fromBackup.reader }
            settings.updateApp { fromBackup.app }
        }

        RestoreSummary(
            books = restored.size,
            quotes = restored.sumOf { it.quotes.size },
            booksWithoutFile = restored.count { it.fileName == null },
        )
    }

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    companion object {
        const val DEFAULT_KEEP = DEFAULT_BACKUPS_KEPT
    }
}
