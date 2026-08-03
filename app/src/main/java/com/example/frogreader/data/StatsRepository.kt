package com.example.frogreader.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Per-day reading time, keyed by ISO date ("2026-07-09"). */
@Serializable
data class ReadingStats(
    val dailySeconds: Map<String, Long> = emptyMap(),
) {
    fun secondsOn(date: LocalDate): Long = dailySeconds[date.toString()] ?: 0L
}

class StatsRepository(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    private val store = AtomicJsonFile(
        file = File(context.filesDir, "reading_stats.json"),
        json = json,
        serializer = ReadingStats.serializer(),
        // dailySeconds has a default, so any JSON object would decode into an
        // empty history and then be written back over the real one.
        looksValid = { it.contains("\"dailySeconds\"") },
    )

    private val _stats = MutableStateFlow(store.readOrDefault(ReadingStats()))
    val stats = _stats.asStateFlow()

    suspend fun addSeconds(date: LocalDate, seconds: Long) {
        if (seconds <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val key = date.toString()
                val updated = ReadingStats(
                    dailySeconds = _stats.value.dailySeconds.toMutableMap().apply {
                        this[key] = (this[key] ?: 0L) + seconds
                    },
                )
                _stats.value = updated
                store.write(updated)
            }
        }
    }

    /** Swaps the whole history — what restoring a backup does. */
    suspend fun replaceAll(replacement: ReadingStats) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                _stats.value = replacement
                store.write(replacement)
            }
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
