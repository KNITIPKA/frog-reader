package com.example.frogreader.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [
        ShelfEntity::class,
        BookEntity::class,
        BookProgressEntity::class,
        QuoteEntity::class,
        BookmarkEntity::class,
        ReadingQueueEntity::class,
        ReadingLogEntity::class,
        DailyReadingStatEntity::class,
        ReadingStatsMetaEntity::class,
        AchievementProgressEntity::class,
        BookFtsEntity::class,
        QuoteFtsEntity::class,
        BookmarkFtsEntity::class,
        ReadingQueueFtsEntity::class,
        ReadingLogFtsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FrogReaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun shelfDao(): ShelfDao
    abstract fun passagesDao(): PassagesDao
    abstract fun readingListsDao(): ReadingListsDao
    abstract fun statsAndAchievementsDao(): StatsAndAchievementsDao
    abstract fun searchDao(): SearchDao
}
