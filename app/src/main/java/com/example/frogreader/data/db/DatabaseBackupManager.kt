package com.example.frogreader.data.db

import androidx.room3.withWriteTransaction
import com.example.frogreader.data.LibraryIndexCorruptedException
import com.example.frogreader.data.backup.BackupFormatException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates snapshot export (`VACUUM INTO` + portable custom font paths) and staged restore
 * validation, file/font path reconciliation, and crash-safe `.pre_restore_bak` database replacement.
 */
object DatabaseBackupManager {

    data class SnapshotAsset(
        val id: String,
        val fileName: String?,
        val coverFileName: String?,
    )

    data class SnapshotExportResult(
        val bookCount: Int,
        val quoteCount: Int,
        val readingQueueCount: Int,
        val readingLogCount: Int,
        val assets: List<SnapshotAsset>,
        val logCoverFileNames: List<String>,
    )

    data class ReconciledRestoreCounts(
        val books: Int,
        val quotes: Int,
        val booksWithoutFile: Int,
        val readingQueueCount: Int,
        val readingLogCount: Int,
    )

    /**
     * Writes a self-contained compacted SQLite snapshot of [filesRoot] into [snapshotDbFile],
     * relativizes per-book custom font paths inside the snapshot against [fontsDir], and returns
     * exact counts and asset references from that same consistent snapshot.
     */
    suspend fun exportSnapshot(
        filesRoot: File,
        snapshotDbFile: File,
        fontsDir: File? = null,
    ): SnapshotExportResult = withContext(Dispatchers.IO) {
        DatabaseManager.vacuumInto(filesRoot, snapshotDbFile)
        val db = DatabaseManager.openStandaloneDatabase(snapshotDbFile)
        try {
            db.withWriteTransaction {
                val bookDao = db.bookDao()
                val passagesDao = db.passagesDao()
                val listsDao = db.readingListsDao()

                val allBooks = bookDao.getAllBooks()
                val allQuotes = passagesDao.getAllQuotes()
                val allQueue = listsDao.getAllQueueEntries()
                val allLogs = listsDao.getAllLogEntries()

                if (fontsDir != null) {
                    for (book in allBooks) {
                        val rawJson = book.readerSettingsJson ?: continue
                        val decoded = DbJson.decodeReaderSettings(rawJson) ?: continue
                        val portable = decoded.relativizeCustomFontPaths(fontsDir)
                        if (portable != decoded) {
                            bookDao.updateBookRaw(
                                book.copy(readerSettingsJson = DbJson.encodeReaderSettings(portable))
                            )
                        }
                    }
                }

                val assetsById = LinkedHashMap<String, SnapshotAsset>()
                for (book in allBooks) {
                    assetsById.putIfAbsent(
                        book.id,
                        SnapshotAsset(
                            id = book.id,
                            fileName = book.fileName,
                            coverFileName = book.coverFileName,
                        ),
                    )
                }
                for (queue in allQueue) {
                    val att = DbJson.decodePrivateAttachment(queue.privateAttachmentJson) ?: continue
                    assetsById.putIfAbsent(
                        att.id,
                        SnapshotAsset(
                            id = att.id,
                            fileName = att.fileName,
                            coverFileName = att.coverFileName,
                        ),
                    )
                }

                val logCovers = allLogs.mapNotNull { it.coverFileName }.distinct()

                SnapshotExportResult(
                    bookCount = allBooks.size,
                    quoteCount = allQuotes.size,
                    readingQueueCount = allQueue.size,
                    readingLogCount = allLogs.size,
                    assets = assetsById.values.toList(),
                    logCoverFileNames = logCovers,
                )
            }
        } finally {
            DatabaseManager.finalizeStandaloneDatabase(db, snapshotDbFile)
        }
    }

    /**
     * Validates [stagedDbFile] (`SQLite magic` + `PRAGMA quick_check` + `PRAGMA foreign_key_check` +
     * Room schema check) without touching any live database. Throws [BackupFormatException] if invalid.
     */
    fun validateStagedOrThrow(stagedDbFile: File) {
        try {
            DatabaseManager.validateStagedDatabase(stagedDbFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: LibraryIndexCorruptedException) {
            throw BackupFormatException("database/frogreader.db inside the backup is damaged: ${e.message}", e)
        } catch (e: Exception) {
            throw BackupFormatException("database/frogreader.db inside the backup is damaged: ${e.message}", e)
        }
    }

    /**
     * Validates [stagedDbFile], reconciles book/cover file availability and rebases custom font paths
     * inside [stagedDbFile], and then atomically swaps [stagedDbFile] into [targetFilesRoot] using
     * `.pre_restore_bak` rollback protection.
     */
    suspend fun restoreStagedIntoLive(
        targetFilesRoot: File,
        stagedDbFile: File,
        booksDir: File?,
        coversDir: File?,
        fontsDir: File?,
        restoredBookFiles: Map<String, String>,
        reconcileFiles: Boolean = true,
        restoredCoverFiles: Map<String, String> = emptyMap(),
        restoredFontFiles: Map<String, String> = emptyMap(),
        beforeCommit: suspend () -> Unit = {},
    ): ReconciledRestoreCounts = withContext(Dispatchers.IO) {
        validateStagedOrThrow(stagedDbFile)

        val counts = reconcileInPlace(
            stagedDbFile = stagedDbFile,
            booksDir = booksDir,
            coversDir = coversDir,
            fontsDir = fontsDir,
            restoredBookFiles = restoredBookFiles,
            reconcileFiles = reconcileFiles,
            restoredCoverFiles = restoredCoverFiles,
            restoredFontFiles = restoredFontFiles,
        )

        DatabaseManager.replaceDatabaseFromStaged(
            filesRoot = targetFilesRoot,
            stagedDbFile = stagedDbFile,
            afterOpenBeforeUnlock = { beforeCommit() },
        )
        counts
    }

    private suspend fun reconcileInPlace(
        stagedDbFile: File,
        booksDir: File?,
        coversDir: File?,
        fontsDir: File?,
        restoredBookFiles: Map<String, String>,
        reconcileFiles: Boolean,
        restoredCoverFiles: Map<String, String>,
        restoredFontFiles: Map<String, String>,
    ): ReconciledRestoreCounts {
        fun isPlainFileName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && name == File(name).name

        fun resolveBookFile(id: String, current: String?): String? {
            if (!reconcileFiles) return current
            return restoredBookFiles[id]
                ?: current?.takeIf { booksDir != null && isPlainFileName(it) && File(booksDir, it).exists() }
        }

        fun resolveCoverFile(current: String?): String? {
            if (!reconcileFiles) return current
            val mapped = restoredCoverFiles[current] ?: current
            return mapped?.takeIf { coversDir != null && isPlainFileName(it) && File(coversDir, it).exists() }
        }

        val db = DatabaseManager.openStandaloneDatabase(stagedDbFile)
        return try {
            db.withWriteTransaction {
                val bookDao = db.bookDao()
                val passagesDao = db.passagesDao()
                val listsDao = db.readingListsDao()

                val allBooks = bookDao.getAllBooks()
                var booksWithoutFile = 0
                for (book in allBooks) {
                    val nextFileName = resolveBookFile(book.id, book.fileName)
                    if (nextFileName == null) booksWithoutFile++
                    val nextCover = resolveCoverFile(book.coverFileName)
                    val nextReaderSettingsJson = book.readerSettingsJson?.let { raw ->
                        DbJson.decodeReaderSettings(raw)
                            ?.rebaseCustomFontPaths(fontsDir, restoredFontFiles)
                            ?.let(DbJson::encodeReaderSettings)
                            ?: raw
                    }
                    val updated = book.copy(
                        fileName = nextFileName,
                        coverFileName = nextCover,
                        readerSettingsJson = nextReaderSettingsJson,
                    )
                    if (updated != book) {
                        bookDao.updateBookRaw(updated)
                    }
                }

                val allQueue = listsDao.getAllQueueEntries()
                for (queue in allQueue) {
                    val rawAtt = queue.privateAttachmentJson ?: continue
                    val att = DbJson.decodePrivateAttachment(rawAtt) ?: continue
                    val updatedAtt = att.copy(
                        fileName = resolveBookFile(att.id, att.fileName),
                        coverFileName = resolveCoverFile(att.coverFileName),
                    )
                    if (updatedAtt != att) {
                        listsDao.updateQueueRaw(
                            queue.copy(privateAttachmentJson = DbJson.encodePrivateAttachment(updatedAtt))
                        )
                    }
                }

                val allLogs = listsDao.getAllLogEntries()
                for (log in allLogs) {
                    val nextCover = resolveCoverFile(log.coverFileName)
                    if (nextCover != log.coverFileName) {
                        listsDao.updateLogRaw(log.copy(coverFileName = nextCover))
                    }
                }

                val quoteCount = passagesDao.getAllQuotes().size
                ReconciledRestoreCounts(
                    books = allBooks.size,
                    quotes = quoteCount,
                    booksWithoutFile = booksWithoutFile,
                    readingQueueCount = allQueue.size,
                    readingLogCount = allLogs.size,
                )
            }
        } finally {
            DatabaseManager.finalizeStandaloneDatabase(db, stagedDbFile)
        }
    }
}
