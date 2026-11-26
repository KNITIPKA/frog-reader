package com.example.frogreader.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery

data class RankedEntityMatch(
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "score")
    val score: Double,
)

data class AchievementMetricsRow(
    @ColumnInfo(name = "finished_books") val finishedBooks: Long,
    @ColumnInfo(name = "reading_seconds") val readingSeconds: Long,
    @ColumnInfo(name = "saved_quotes") val savedQuotes: Long,
    @ColumnInfo(name = "queued_books") val queuedBooks: Long,
)

@Dao
interface SearchDao {

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM reading_log WHERE status = 'FINISHED') +
            (SELECT COUNT(*) FROM books WHERE status = 'FINISHED' AND id NOT IN (
                SELECT linked_book_id FROM reading_log
                WHERE status = 'FINISHED' AND linked_book_id IS NOT NULL
            )) AS finished_books,
            MAX(
                (SELECT COALESCE(SUM(seconds), 0) FROM daily_reading_stats),
                (SELECT COALESCE(SUM(reading_seconds), 0) FROM books),
                (SELECT COALESCE(SUM(reading_seconds), 0) FROM reading_log)
            ) AS reading_seconds,
            (SELECT COUNT(*) FROM quotes) AS saved_quotes,
            (SELECT COUNT(*) FROM reading_queue) AS queued_books
        """
    )
    suspend fun getAchievementMetrics(): AchievementMetricsRow

    // Fetch only verified candidates instead of materializing every saved passage on each keypress.
    // Callers chunk ids below SQLite's host-parameter limit and restore FTS relevance order.
    @Query("SELECT * FROM quotes WHERE id IN (:ids)")
    suspend fun getQuoteCandidates(ids: List<String>): List<QuoteEntity>

    @Query("SELECT * FROM bookmarks WHERE id IN (:ids)")
    suspend fun getBookmarkCandidates(ids: List<String>): List<BookmarkEntity>

    @Query("SELECT * FROM reading_queue WHERE id IN (:ids)")
    suspend fun getQueueCandidates(ids: List<String>): List<ReadingQueueEntity>

    @Query("SELECT * FROM reading_log WHERE id IN (:ids)")
    suspend fun getLogCandidates(ids: List<String>): List<ReadingLogEntity>

    @RawQuery(observedEntities = [BookEntity::class, BookFtsEntity::class])
    suspend fun rawRankBooks(query: RoomRawQuery): List<RankedEntityMatch>

    @RawQuery(observedEntities = [QuoteEntity::class, QuoteFtsEntity::class])
    suspend fun rawRankQuotes(query: RoomRawQuery): List<RankedEntityMatch>

    @RawQuery(observedEntities = [BookmarkEntity::class, BookmarkFtsEntity::class])
    suspend fun rawRankBookmarks(query: RoomRawQuery): List<RankedEntityMatch>

    @RawQuery(observedEntities = [ReadingQueueEntity::class, ReadingQueueFtsEntity::class])
    suspend fun rawRankReadingQueue(query: RoomRawQuery): List<RankedEntityMatch>

    @RawQuery(observedEntities = [ReadingLogEntity::class, ReadingLogFtsEntity::class])
    suspend fun rawRankReadingLog(query: RoomRawQuery): List<RankedEntityMatch>

    // Arabic/Hebrew clitic candidates (`الكتاب`, `והספר`) that the FTS5 prefix index cannot see.
    // C-speed `instr()` over already-normalized FTS columns; every row is re-verified in Kotlin
    // with word-prefix + clitic rules, so these never produce mid-word Latin/Cyrillic matches.

    @Query(
        """
        SELECT entity_id
        FROM books_fts
        WHERE instr(title, :normToken) > 0
           OR instr(author, :normToken) > 0
           OR instr(series, :normToken) > 0
           OR instr(description, :normToken) > 0
        """
    )
    suspend fun findBooksByNormalizedSubstring(normToken: String): List<String>

    @Query(
        """
        SELECT entity_id
        FROM quotes_fts
        WHERE instr(text, :normToken) > 0
           OR instr(note, :normToken) > 0
        """
    )
    suspend fun findQuotesByNormalizedSubstring(normToken: String): List<String>

    @Query(
        """
        SELECT entity_id
        FROM bookmarks_fts
        WHERE instr(preview, :normToken) > 0
        """
    )
    suspend fun findBookmarksByNormalizedSubstring(normToken: String): List<String>

    @Query(
        """
        SELECT entity_id
        FROM reading_queue_fts
        WHERE instr(title, :normToken) > 0
           OR instr(author, :normToken) > 0
           OR instr(note, :normToken) > 0
        """
    )
    suspend fun findReadingQueueByNormalizedSubstring(normToken: String): List<String>

    @Query(
        """
        SELECT entity_id
        FROM reading_log_fts
        WHERE instr(title, :normToken) > 0
           OR instr(author, :normToken) > 0
           OR instr(series, :normToken) > 0
           OR instr(review, :normToken) > 0
        """
    )
    suspend fun findReadingLogByNormalizedSubstring(normToken: String): List<String>
}
