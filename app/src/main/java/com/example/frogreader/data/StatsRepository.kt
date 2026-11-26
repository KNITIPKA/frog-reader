package com.example.frogreader.data

import android.content.Context
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.buildReadingStats
import com.example.frogreader.data.db.toEntities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

/** Per-day reading time, keyed by ISO date ("2026-07-09"). */
@Serializable
data class ReadingStats(
    val dailySeconds: Map<String, Long> = emptyMap(),
    /** End of the latest qualified foreground reading. */
    val lastReadAtMillis: Long? = null,
) {
    fun secondsOn(date: LocalDate): Long = dailySeconds[date.toString()] ?: 0L
}

class StatsRepository(
    context: Context? = null,
    filesRootOverride: File? = null,
) {
    private val filesRoot: File = filesRootOverride ?: context?.filesDir ?: File("build/tmp/test_files")
    private val mutex = Mutex()

    private val _stats by lazy { MutableStateFlow(loadInitialStats()) }
    val stats get() = _stats.asStateFlow()

    private fun loadInitialStats(): ReadingStats = runBlocking(Dispatchers.IO) {
        DatabaseManager.withSwapLock(filesRoot) { db ->
            db.withReadTransaction {
                val dao = db.statsAndAchievementsDao()
                buildReadingStats(dao.getAllDailyStats(), dao.getStatsMeta())
            }
        }
    }

    suspend fun addSeconds(date: LocalDate, seconds: Long, lastReadAtMillis: Long? = null) {
        if (seconds <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Initialize before committing: a lazy load after the write already includes
                // this increment. Publish the committed database state so another writer's
                // increments are retained as well.
                val state = _stats
                val updated = DatabaseManager.withSwapLock(filesRoot) { db ->
                    db.withWriteTransaction {
                        val dao = db.statsAndAchievementsDao()
                        dao.addReadingSecondsForDate(
                            isoDate = date.toString(),
                            deltaSeconds = seconds,
                            lastReadAtMillis = lastReadAtMillis,
                        )
                        buildReadingStats(dao.getAllDailyStats(), dao.getStatsMeta())
                    }
                }
                state.value = updated
            }
        }
    }

    /** Caller holds BookRepository's write lock; publish both models after its shared transaction. */
    internal suspend fun updateWithBookTransaction(
        bookFilesRoot: File,
        block: suspend () -> ReadingStats,
    ) {
        require(filesRoot.canonicalFile == bookFilesRoot.canonicalFile) { "Reading counters must share one database." }
        mutex.withLock {
            val state = _stats
            state.value = block()
        }
    }

    /** Swaps the whole history in SQLite. */
    suspend fun replaceAll(replacement: ReadingStats) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (daily, meta) = replacement.toEntities()
                DatabaseManager.withSwapLock(filesRoot) { db ->
                    db.withWriteTransaction {
                        db.statsAndAchievementsDao().replaceAllStats(daily, meta)
                    }
                }
                _stats.value = replacement
            }
        }
    }

    /** Reloads stats from the active SQLite database (e.g. after hot-swap backup restore). */
    suspend fun reloadFromDatabase() = withContext(Dispatchers.IO) {
        mutex.withLock { reloadStatsLocked() }
    }

    private suspend fun reloadStatsLocked() {
        val reloaded = DatabaseManager.withSwapLock(filesRoot) { db ->
            db.withReadTransaction {
                val dao = db.statsAndAchievementsDao()
                buildReadingStats(dao.getAllDailyStats(), dao.getStatsMeta())
            }
        }
        _stats.value = reloaded
    }

    /** Keep pending increments behind the restored database and its matching in-memory state. */
    internal suspend fun <T> withRestoreLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = runCatching { block() }
            val refreshed = runCatching {
                withContext(NonCancellable) { reloadStatsLocked() }
            }
            result.exceptionOrNull()?.let { failure ->
                refreshed.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            refreshed.getOrThrow()
            result.getOrThrow()
        }
    }

}

/**
 * Consecutive days (ending today, or yesterday if today isn't counted yet)
 * with at least [thresholdMinutes] minutes of reading.
 */
fun ReadingStats.streakDays(thresholdMinutes: Int, today: LocalDate = LocalDate.now()): Int {
    val thresholdSeconds = thresholdMinutes.coerceAtLeast(1) * 60L
    var streak = 0
    var day = today
    if (secondsOn(day) < thresholdSeconds) day = day.minusDays(1)
    while (secondsOn(day) >= thresholdSeconds) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}
