package com.example.frogreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface StatsAndAchievementsDao {

    @Query("SELECT * FROM daily_reading_stats ORDER BY iso_date ASC")
    suspend fun getAllDailyStats(): List<DailyReadingStatEntity>

    @Query("SELECT * FROM daily_reading_stats WHERE iso_date = :isoDate LIMIT 1")
    suspend fun getDailyStat(isoDate: String): DailyReadingStatEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyStatIgnore(entity: DailyReadingStatEntity): Long

    @Update
    suspend fun updateDailyStatRaw(entity: DailyReadingStatEntity)

    @Query("DELETE FROM daily_reading_stats")
    suspend fun clearAllDailyStats()

    @Query("SELECT * FROM reading_stats_meta WHERE singleton_id = 1 LIMIT 1")
    suspend fun getStatsMeta(): ReadingStatsMetaEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatsMetaIgnore(entity: ReadingStatsMetaEntity): Long

    @Update
    suspend fun updateStatsMetaRaw(entity: ReadingStatsMetaEntity)

    @Query("DELETE FROM reading_stats_meta")
    suspend fun clearStatsMeta()

    @Transaction
    suspend fun addReadingSecondsForDate(isoDate: String, deltaSeconds: Long, lastReadAtMillis: Long?) {
        val existing = getDailyStat(isoDate)
        if (existing == null) {
            insertDailyStatIgnore(DailyReadingStatEntity(isoDate = isoDate, seconds = deltaSeconds))
        } else {
            updateDailyStatRaw(existing.copy(seconds = existing.seconds + deltaSeconds))
        }
        if (lastReadAtMillis != null && lastReadAtMillis > 0L) {
            val meta = getStatsMeta()
            val nextLastRead = listOfNotNull(meta?.lastReadAtMillis, lastReadAtMillis).maxOrNull()
            val updatedMeta = ReadingStatsMetaEntity(singletonId = 1, lastReadAtMillis = nextLastRead)
            if (insertStatsMetaIgnore(updatedMeta) == -1L) {
                updateStatsMetaRaw(updatedMeta)
            }
        }
    }

    @Transaction
    suspend fun replaceAllStats(daily: List<DailyReadingStatEntity>, meta: ReadingStatsMetaEntity) {
        clearAllDailyStats()
        clearStatsMeta()
        for (row in daily) {
            insertDailyStatIgnore(row)
        }
        insertStatsMetaIgnore(meta)
    }

    // ----------------------- Achievements -----------------------

    @Query("SELECT * FROM achievement_progress")
    suspend fun getAllAchievementProgress(): List<AchievementProgressEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAchievementIgnore(entity: AchievementProgressEntity): Long

    @Update
    suspend fun updateAchievementRaw(entity: AchievementProgressEntity)

    @Query("DELETE FROM achievement_progress")
    suspend fun clearAllAchievements()

    suspend fun upsertAchievementProgress(entity: AchievementProgressEntity) {
        if (insertAchievementIgnore(entity) == -1L) {
            updateAchievementRaw(entity)
        }
    }
}
