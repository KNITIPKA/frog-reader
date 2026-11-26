package com.example.frogreader.data.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.frogreader.data.LibraryIndexCorruptedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Coordinates lifecycle, WAL configuration, `VACUUM INTO` snapshots, and crash-safe restore swaps
 * for [FrogReaderDatabase] instances keyed by their canonical database file path.
 */
object DatabaseManager {

    const val DATABASE_FILE_NAME = "frogreader.db"
    const val DATABASES_SUBDIR = "databases"

    private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

    private val registryLock = Any()
    private val instances = HashMap<String, ManagedHandle>()

    private class ManagedHandle(val dbFile: File) {
        val swapMutex = Mutex()
        @Volatile
        var database: FrogReaderDatabase? = null

        fun getOrOpen(): FrogReaderDatabase = open(recover = true)

        fun getOrOpenWithoutRecovery(): FrogReaderDatabase = open(recover = false)

        private fun open(recover: Boolean): FrogReaderDatabase {
            database?.let { return it }
            synchronized(this) {
                database?.let { return it }
                if (recover) recoverInterruptedRestore(dbFile)
                validateExistingFileHeader(dbFile)
                dbFile.parentFile?.mkdirs()
                val opened = buildDatabase(dbFile)
                database = opened
                return opened
            }
        }

        fun close() {
            synchronized(this) {
                val existing = database
                existing?.close()
                database = null
            }
        }
    }

    fun databaseFileFor(filesRoot: File): File =
        File(File(filesRoot, DATABASES_SUBDIR), DATABASE_FILE_NAME)

    fun getDatabase(filesRoot: File): FrogReaderDatabase =
        handleFor(databaseFileFor(filesRoot)).getOrOpen()

    suspend fun <T> withSwapLock(filesRoot: File, block: suspend (FrogReaderDatabase) -> T): T {
        val handle = handleFor(databaseFileFor(filesRoot))
        return handle.swapMutex.withLock {
            block(handle.getOrOpen())
        }
    }

    /**
     * Produces a single-file, compacted, journal-free SQLite snapshot of the live database using
     * native `VACUUM INTO`, suitable for embedding directly inside a backup archive.
     */
    suspend fun vacuumInto(filesRoot: File, targetFile: File) = withContext(Dispatchers.IO) {
        val handle = handleFor(databaseFileFor(filesRoot))
        handle.swapMutex.withLock {
            try {
                val db = handle.getOrOpen()
                targetFile.parentFile?.mkdirs()
                if (targetFile.exists() && !targetFile.delete()) {
                    throw IOException("Could not clear existing snapshot target: ${targetFile.absolutePath}")
                }
                val escapedPath = targetFile.absolutePath.replace("'", "''")
                db.useWriterConnection { transactor ->
                    transactor.executeSQL("PRAGMA wal_checkpoint(PASSIVE)")
                    transactor.executeSQL("VACUUM INTO '$escapedPath'")
                }
            } catch (e: LibraryIndexCorruptedException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                if (msg.contains("not a database", ignoreCase = true) ||
                    msg.contains("malformed", ignoreCase = true) ||
                    msg.contains("corrupt", ignoreCase = true)
                ) {
                    throw LibraryIndexCorruptedException("SQLite database is corrupted: $msg", e)
                }
                throw e
            }
        }
    }

    /** Opens a standalone Room database file in `TRUNCATE` journal mode for snapshot/staging work. */
    internal fun openStandaloneDatabase(dbFile: File): FrogReaderDatabase =
        buildDatabase(dbFile, journalMode = RoomDatabase.JournalMode.TRUNCATE)

    /** Ensures [db] leaves [dbFile] as a clean, self-contained single file with no journal sidecars. */
    internal suspend fun finalizeStandaloneDatabase(db: FrogReaderDatabase, dbFile: File) =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                db.useWriterConnection { it.executeSQL("PRAGMA journal_mode = DELETE") }
            } finally {
                db.close()
            }
            requireNoHotSidecars(dbFile)
            syncFile(dbFile)
        }

    /**
     * Validates a staged SQLite database file (`PRAGMA quick_check` + `PRAGMA foreign_key_check` +
     * Room schema verification) without touching the active database.
     */
    fun validateStagedDatabase(stagedDbFile: File) {
        if (!stagedDbFile.exists() || stagedDbFile.length() < SQLITE_MAGIC.size) {
            throw IOException("Backup database file is missing or truncated.")
        }
        validateExistingFileHeader(stagedDbFile)

        val driver = BundledSQLiteDriver()
        val conn = driver.open(stagedDbFile.absolutePath)
        try {
            conn.execSQL("PRAGMA foreign_keys = ON")
            conn.prepare("PRAGMA user_version").use { statement ->
                if (!statement.step() || statement.getLong(0) < 1) {
                    throw IOException("Backup database has no supported SQLite schema version.")
                }
            }
            conn.prepare("PRAGMA quick_check").use { stmt ->
                if (!stmt.step()) {
                    throw IOException("SQLite quick_check returned no rows.")
                }
                val result = stmt.getText(0)
                if (!result.equals("ok", ignoreCase = true)) {
                    throw IOException("SQLite quick_check failed: $result")
                }
            }
            conn.prepare("PRAGMA foreign_key_check").use { stmt ->
                if (stmt.step()) {
                    val table = stmt.getText(0)
                    throw IOException("SQLite foreign_key_check failed on table '$table'.")
                }
            }
            // Only staging is modified: removing the cached identity makes Room inspect
            // every table/index instead of trusting a copied room_master_table hash.
            conn.execSQL("DROP TABLE IF EXISTS room_master_table")
        } finally {
            conn.close()
        }

        // Also open briefly through Room to verify exact schema hash/table compatibility.
        val tempRoom = openStandaloneDatabase(stagedDbFile)
        try {
            // Trigger connection open & Room schema validation synchronously via driver check:
            kotlinx.coroutines.runBlocking {
                tempRoom.bookDao().getAllBookIds()
            }
        } finally {
            kotlinx.coroutines.runBlocking { finalizeStandaloneDatabase(tempRoom, stagedDbFile) }
        }
        driver.open(stagedDbFile.absolutePath).use { connection ->
            DatabaseIntegrityChecks.validateContent(connection)
            DatabaseIntegrityChecks.validateSearchIndexes(connection)
        }
        syncFile(stagedDbFile)
    }

    /**
     * Atomically replaces the active database with a validated [stagedDbFile], preserving a
     * `.pre_restore_bak` rollback copy until the new database opens cleanly.
     */
    suspend fun replaceDatabaseFromStaged(
        filesRoot: File,
        stagedDbFile: File,
        afterOpenBeforeUnlock: suspend (FrogReaderDatabase) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        validateStagedDatabase(stagedDbFile)
        val dbFile = databaseFileFor(filesRoot)
        val handle = handleFor(dbFile)
        handle.swapMutex.withLock {
            dbFile.parentFile?.mkdirs()
            val backupFile = restoreBackupFile(dbFile)
            val incomingFile = File(dbFile.parentFile, "$DATABASE_FILE_NAME.restore_new")
            // Copy and fsync BEFORE touching live data. Disk-full/cancellation leaves it intact.
            try {
                stagedDbFile.inputStream().use { input ->
                    FileOutputStream(incomingFile).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
            } catch (failure: Throwable) {
                incomingFile.delete()
                throw failure
            }
            // Once the first rename happens, cancellation must not interrupt commit/rollback.
            withContext(NonCancellable) {
                var installed = false
                try {
                    handle.close()
                    recoverInterruptedRestore(dbFile)
                    if (dbFile.exists()) {
                        // SQLite recovers any WAL and refuses DELETE mode while another connection
                        // still uses it. Never discard a possibly uncheckpointed WAL.
                        makeSelfContained(dbFile)
                        atomicMove(dbFile, backupFile)
                    }
                    atomicMove(incomingFile, dbFile)
                    installed = true
                    val reopened = handle.getOrOpenWithoutRecovery()
                    // Room opens lazily: force schema validation before declaring success.
                    reopened.bookDao().getAllBookIds()
                    afterOpenBeforeUnlock(reopened)
                    if (backupFile.exists() && !backupFile.delete()) {
                        throw IOException("Could not commit restored database; rollback copy retained.")
                    }
                } catch (failure: Throwable) {
                    try {
                        handle.close()
                        if (backupFile.exists()) {
                            recoverInterruptedRestore(dbFile)
                        } else if (installed) {
                            deleteSidecars(dbFile)
                            if (!dbFile.delete()) throw IOException("Could not remove uncommitted database.")
                        }
                    } catch (rollbackFailure: Throwable) {
                        // Preserve the rollback file for startup recovery, including if disk is full.
                        failure.addSuppressed(rollbackFailure)
                    }
                    throw failure
                } finally {
                    incomingFile.delete()
                }
            }
        }
    }

    /** Closes and unregisters any database instance under [filesRoot] (used by test fixtures). */
    fun closeForFilesRoot(filesRoot: File) {
        val dbFile = databaseFileFor(filesRoot)
        val key = canonicalKey(dbFile)
        val removed = synchronized(registryLock) { instances[key] } ?: return
        kotlinx.coroutines.runBlocking {
            removed.swapMutex.withLock {
                removed.close()
                synchronized(registryLock) { instances.remove(key, removed) }
            }
        }
    }

    /** Closes any open instance and deletes the database + WAL/SHM files under [filesRoot]. */
    fun closeAndDeleteForFilesRoot(filesRoot: File) {
        closeForFilesRoot(filesRoot)
        val dbFile = databaseFileFor(filesRoot)
        dbFile.delete()
        deleteSidecars(dbFile)
        restoreBackupFile(dbFile).delete()
        File(dbFile.parentFile, "$DATABASE_FILE_NAME.restore_new").delete()
    }

    private fun handleFor(dbFile: File): ManagedHandle {
        val key = canonicalKey(dbFile)
        return synchronized(registryLock) {
            instances.getOrPut(key) { ManagedHandle(dbFile) }
        }
    }

    private fun canonicalKey(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    private fun deleteSidecars(dbFile: File) {
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
        File("${dbFile.absolutePath}-journal").delete()
    }

    fun restoreSettingsBackupFileFor(filesRoot: File): File =
        File(databaseFileFor(filesRoot).parentFile, "restore_previous_settings.json")

    fun restoreSettingsRollbackMarkerFileFor(filesRoot: File): File =
        File(databaseFileFor(filesRoot).parentFile, "restore_settings_rollback")

    private fun restoreBackupFile(dbFile: File) = File(dbFile.parentFile, "${dbFile.name}.pre_restore_bak")

    private fun atomicMove(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** A retained backup means the previous restore did not reach its commit point. */
    private fun recoverInterruptedRestore(dbFile: File) {
        val backup = restoreBackupFile(dbFile)
        if (!backup.exists()) return
        validateExistingFileHeader(backup)
        val previousSettings = File(dbFile.parentFile, "restore_previous_settings.json")
        if (previousSettings.exists()) {
            val marker = File(dbFile.parentFile, "restore_settings_rollback")
            FileOutputStream(marker).use { it.fd.sync() }
        }
        // These sidecars belong to the uncommitted replacement, never to the checkpointed backup.
        deleteSidecars(dbFile)
        atomicMove(backup, dbFile)
    }

    private fun makeSelfContained(dbFile: File) {
        BundledSQLiteDriver().open(dbFile.absolutePath).use { connection ->
            connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { statement ->
                if (statement.step() && statement.getLong(0) != 0L) {
                    throw IOException("Live database is still busy; restore was not applied.")
                }
            }
            connection.prepare("PRAGMA journal_mode = DELETE").use { statement ->
                if (!statement.step() || !statement.getText(0).equals("delete", ignoreCase = true)) {
                    throw IOException("Could not safely checkpoint the live database.")
                }
            }
        }
        requireNoHotSidecars(dbFile)
        syncFile(dbFile)
    }

    private fun requireNoHotSidecars(dbFile: File) {
        for (suffix in listOf("-wal", "-journal")) {
            val sidecar = File("${dbFile.absolutePath}$suffix")
            if (sidecar.exists() && sidecar.length() > 0) {
                throw IOException("Database has an outstanding SQLite journal: ${sidecar.name}")
            }
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun validateExistingFileHeader(file: File) {
        if (!file.exists()) return
        val len = file.length()
        if (len == 0L) return
        if (len < SQLITE_MAGIC.size) {
            throw LibraryIndexCorruptedException("SQLite database file is corrupted (truncated header): ${file.name}")
        }
        val header = ByteArray(SQLITE_MAGIC.size)
        val read = FileInputStream(file).use { it.read(header) }
        if (read != SQLITE_MAGIC.size || !header.contentEquals(SQLITE_MAGIC)) {
            throw LibraryIndexCorruptedException("SQLite database file is corrupted (invalid header): ${file.name}")
        }
    }

    private fun buildDatabase(
        dbFile: File,
        journalMode: RoomDatabase.JournalMode = RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING,
    ): FrogReaderDatabase =
        Room.databaseBuilder<FrogReaderDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(DatabaseMigrations.MIGRATION_1_2)
            .setJournalMode(journalMode)
            .setQueryCoroutineContext(Dispatchers.IO)
            .addCallback(object : RoomDatabase.Callback() {
                override suspend fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA foreign_keys = ON")
                    connection.execSQL("PRAGMA busy_timeout = 5000")
                    connection.execSQL("PRAGMA temp_store = MEMORY")
                }
            })
            .build()
}
