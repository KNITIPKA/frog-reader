package com.example.frogreader.data.backup

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.frogreader.FrogReaderApp
import com.example.frogreader.data.BackupFrequency
import com.example.frogreader.data.model.BackupMode
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Writes a backup on a schedule, so having one does not depend on the user
 * remembering to ask for one.
 *
 * Always DATA mode. A scheduled job runs unattended over whatever connection
 * the folder's provider uses, and pushing a multi-gigabyte archive to a cloud
 * folder in the background is a good way to burn a data plan; the data-only
 * archive is a few hundred KB and holds everything that cannot be recovered
 * any other way.
 */
class ScheduledBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FrogReaderApp ?: return Result.success()
        val settings = app.settingsRepository

        if (settings.backupFrequency.first() == BackupFrequency.OFF) return Result.success()
        val folder = settings.backupFolder.first() ?: return Result.success()

        val target = SafFolderTarget(app, Uri.parse(folder))
        return try {
            app.backupRepository.export(target, BackupMode.DATA)
            target.rotate()
            settings.recordBackupAt(System.currentTimeMillis())
            Result.success()
        } catch (e: Exception) {
            // The folder may be temporarily unmounted, or a cloud provider may
            // be offline. Retrying is right; giving up silently is not.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val WORK_NAME = "scheduled-backup"

        /**
         * Brings the schedule in line with the setting. Safe to call whenever
         * either the folder or the frequency changes.
         */
        fun apply(context: Context, frequency: BackupFrequency) {
            val work = WorkManager.getInstance(context)
            if (frequency == BackupFrequency.OFF) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }

            val days = if (frequency == BackupFrequency.DAILY) 1L else 7L
            val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(days, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // Not on battery and not while the phone is in use: a
                        // backup is never urgent enough to be noticed.
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build(),
                )
                .build()

            work.enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP would ignore a change of frequency until the existing
                // schedule happened to expire.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
