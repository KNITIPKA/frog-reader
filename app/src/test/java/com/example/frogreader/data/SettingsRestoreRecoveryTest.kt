package com.example.frogreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.PdfToolbarAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/** Real DataStore preferences and native SQLite exercise the cross-store recovery protocol. */
class SettingsRestoreRecoveryTest {
    @get:Rule val directory = TemporaryFolder()
    private val root: File get() = directory.root
    private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences by lazy {
        PreferenceDataStoreFactory.create(scope = preferencesScope) {
            File(root, "datastore/restore.preferences_pb")
        }
    }

    private fun repository(context: Context = context()) = SettingsRepository(context, preferences)

    private fun context(): Context = mock<Context>().also { context ->
        val hints = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(root)
        whenever(context.getSharedPreferences("boot_hints", Context.MODE_PRIVATE)).thenReturn(hints)
        whenever(hints.all).thenReturn(emptyMap<String, Any>())
    }

    private val original = BackupSettings(
        reader = ReaderSettings(fontSizeSp = 21f, font = ReaderFont.SANS),
        app = AppSettings(theme = AppTheme.OLED, profileName = "Original", dynamicColor = true),
    )
    private val restored = BackupSettings(
        reader = ReaderSettings(fontSizeSp = 16f, font = ReaderFont.SERIF, pdfSinglePage = true),
        app = AppSettings(theme = AppTheme.SEPIA, profileName = "Restored", haptics = false),
    )

    @After
    fun closeDatabase() {
        preferencesScope.cancel()
        DatabaseManager.closeForFilesRoot(root)
    }

    @Test
    fun `PDF toolbar appearance defaults to glass and survives settings restore`() = runTest {
        val repository = repository()
        assertEquals(PdfToolbarAppearance.GLASS, repository.appSettings.first().pdfToolbarAppearance)

        repository.updateApp { it.copy(pdfToolbarAppearance = PdfToolbarAppearance.CLEAR) }
        val snapshot = repository.snapshotForBackup()
        assertEquals(PdfToolbarAppearance.CLEAR, snapshot.app.pdfToolbarAppearance)

        repository.updateApp { it.copy(pdfToolbarAppearance = PdfToolbarAppearance.SOLID) }
        assertEquals(PdfToolbarAppearance.SOLID, repository().appSettings.first().pdfToolbarAppearance)

        repository.applyBackupSettings(snapshot)
        assertEquals(snapshot.app, repository().appSettings.first())
    }

    @Test
    fun `settings rollback restores both groups and preserves device local preferences`() = runTest {
        val repository = repository()
        repository.applyBackupSettings(original)
        repository.setBackupFolder("content://device-local/tree/backup")
        repository.setBackupFrequency(BackupFrequency.WEEKLY)
        val deviceId = repository.deviceId()
        repository.prepareRestoreSettings()
        assertTrue(DatabaseManager.restoreSettingsBackupFileFor(root).isFile)
        repository.applyBackupSettings(restored)
        assertEquals(restored.reader, repository.settings.first())
        assertEquals(restored.app, repository.appSettings.first())
        DatabaseManager.restoreSettingsRollbackMarkerFileFor(root).writeText("")
        repository.rollbackRestoreSettings()
        assertEquals(original.reader, repository.settings.first())
        assertEquals(original.app, repository.appSettings.first())
        assertEquals("content://device-local/tree/backup", repository.backupFolder.first())
        assertEquals(BackupFrequency.WEEKLY, repository.backupFrequency.first())
        assertEquals(deviceId, repository.deviceId())
        assertFalse(DatabaseManager.restoreSettingsBackupFileFor(root).exists())
        assertFalse(DatabaseManager.restoreSettingsRollbackMarkerFileFor(root).exists())
    }

    @Test
    fun `startup rolls back settings when interrupted database swap retains its backup`() = runTest {
        val context = context()
        val repository = repository(context)
        repository.applyBackupSettings(original)
        repository.prepareRestoreSettings()
        val database = DatabaseManager.databaseFileFor(root)
        val backup = File(database.parentFile, "${database.name}.pre_restore_bak")
        DatabaseManager.vacuumInto(root, backup)
        repository.applyBackupSettings(restored)
        DatabaseManager.closeForFilesRoot(root)

        val restarted = repository(context)
        assertEquals(original.app, restarted.appSettings.first())
        assertEquals(original.reader, restarted.settings.first())
        assertFalse(backup.exists())
        assertFalse(DatabaseManager.restoreSettingsBackupFileFor(root).exists())
        assertFalse(DatabaseManager.restoreSettingsRollbackMarkerFileFor(root).exists())
    }

    @Test
    fun `startup keeps committed settings when only their previous snapshot remains`() = runTest {
        val context = context()
        val repository = repository(context)
        repository.applyBackupSettings(original)
        repository.prepareRestoreSettings()
        repository.applyBackupSettings(restored)
        // The database commit removed pre_restore_bak; a process exit preceded settings cleanup.
        DatabaseManager.closeForFilesRoot(root)
        val restarted = repository(context)
        assertEquals(restored.reader, restarted.settings.first())
        assertEquals(restored.app, restarted.appSettings.first())
        assertFalse(DatabaseManager.restoreSettingsBackupFileFor(root).exists())
    }


    @Test
    fun `late rollback cleanup keeps settings after database commit`() = runTest {
        val repository = repository()
        repository.applyBackupSettings(original)
        repository.prepareRestoreSettings()
        repository.applyBackupSettings(restored)
        repository.rollbackRestoreSettings()
        assertEquals(restored.reader, repository.settings.first())
        assertEquals(restored.app, repository.appSettings.first())
        assertFalse(DatabaseManager.restoreSettingsBackupFileFor(root).exists())
    }

    @Test
    fun `missing rollback payload is surfaced and its marker retained`() = runTest {
        val repository = repository()
        DatabaseManager.restoreSettingsRollbackMarkerFileFor(root).apply {
            parentFile!!.mkdirs()
            writeText("")
        }
        try {
            repository.appSettings.first()
            fail("A rollback without previous settings must be reported")
        } catch (failure: java.io.IOException) {
            assertTrue(failure.message.orEmpty().contains("Previous settings"))
            assertTrue(DatabaseManager.restoreSettingsRollbackMarkerFileFor(root).exists())
        }
    }
}
