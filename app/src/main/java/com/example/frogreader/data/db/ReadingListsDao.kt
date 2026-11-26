package com.example.frogreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface ReadingListsDao {

    // ----------------------- Reading Queue -----------------------

    @Query("SELECT * FROM reading_queue ORDER BY priority_order ASC, doc_id ASC")
    suspend fun getAllQueueEntries(): List<ReadingQueueEntity>

    @Query("SELECT * FROM reading_queue WHERE id = :id LIMIT 1")
    suspend fun getQueueEntryById(id: String): ReadingQueueEntity?

    @Query("SELECT * FROM reading_queue WHERE linked_book_id = :bookId")
    suspend fun getQueueEntriesForBook(bookId: String): List<ReadingQueueEntity>

    @Query("SELECT doc_id FROM reading_queue WHERE id = :id LIMIT 1")
    suspend fun getQueueDocId(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQueueRaw(entity: ReadingQueueEntity): Long

    @Update
    suspend fun updateQueueRaw(entity: ReadingQueueEntity)

    @Query("DELETE FROM reading_queue WHERE id = :id")
    suspend fun deleteQueueRowById(id: String): Int

    @Query("DELETE FROM reading_queue_fts WHERE rowid = :rowId")
    suspend fun deleteQueueFtsByRowId(rowId: Long)

    @Query(
        """
        INSERT INTO reading_queue_fts(rowid, entity_id, title, author, note)
        VALUES (:rowId, :entityId, :title, :author, :note)
        """
    )
    suspend fun insertQueueFtsRaw(
        rowId: Long,
        entityId: String,
        title: String,
        author: String,
        note: String,
    )

    @Query("DELETE FROM reading_queue_fts")
    suspend fun clearAllQueueFts()

    @Query("DELETE FROM reading_queue")
    suspend fun clearAllQueue()

    @Transaction
    suspend fun saveQueueEntry(entity: ReadingQueueEntity, syncFts: Boolean = true): Long {
        val existingDocId = if (entity.docId > 0L) entity.docId else getQueueDocId(entity.id)
        val docId = if (existingDocId == null) {
            insertQueueRaw(entity.copy(docId = 0L))
        } else {
            updateQueueRaw(entity.copy(docId = existingDocId))
            existingDocId
        }
        if (syncFts) {
            val fts = entity.toFts(docId)
            deleteQueueFtsByRowId(docId)
            insertQueueFtsRaw(docId, fts.entityId, fts.title, fts.author, fts.note)
        }
        return docId
    }

    @Transaction
    suspend fun deleteQueueEntryById(id: String): Boolean {
        val docId = getQueueDocId(id) ?: return false
        deleteQueueFtsByRowId(docId)
        deleteQueueRowById(id)
        return true
    }

    // ------------------------ Reading Log ------------------------

    @Query("SELECT * FROM reading_log ORDER BY manual_order ASC, doc_id ASC")
    suspend fun getAllLogEntries(): List<ReadingLogEntity>

    @Query("SELECT * FROM reading_log WHERE id = :id LIMIT 1")
    suspend fun getLogEntryById(id: String): ReadingLogEntity?

    @Query("SELECT * FROM reading_log WHERE linked_book_id = :bookId ORDER BY manual_order ASC, doc_id ASC")
    suspend fun getLogEntriesForBook(bookId: String): List<ReadingLogEntity>

    @Query("SELECT doc_id FROM reading_log WHERE id = :id LIMIT 1")
    suspend fun getLogDocId(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLogRaw(entity: ReadingLogEntity): Long

    @Update
    suspend fun updateLogRaw(entity: ReadingLogEntity)

    @Query("DELETE FROM reading_log WHERE id = :id")
    suspend fun deleteLogRowById(id: String): Int

    @Query("DELETE FROM reading_log_fts WHERE rowid = :rowId")
    suspend fun deleteLogFtsByRowId(rowId: Long)

    @Query(
        """
        INSERT INTO reading_log_fts(rowid, entity_id, title, author, series, review)
        VALUES (:rowId, :entityId, :title, :author, :series, :review)
        """
    )
    suspend fun insertLogFtsRaw(
        rowId: Long,
        entityId: String,
        title: String,
        author: String,
        series: String,
        review: String,
    )

    @Query("DELETE FROM reading_log_fts")
    suspend fun clearAllLogFts()

    @Query("DELETE FROM reading_log")
    suspend fun clearAllLog()

    @Query(
        """
        UPDATE reading_log
        SET reading_seconds = :readingSeconds,
            updated_at_millis = :nowMillis
        WHERE id = :logId
        """
    )
    suspend fun updateLogReadingSeconds(logId: String, readingSeconds: Long, nowMillis: Long): Int

    @Query(
        """
        UPDATE reading_log
        SET last_opened_at_millis = :lastOpenedAtMillis,
            updated_at_millis = :nowMillis
        WHERE id = :logId
        """
    )
    suspend fun updateLogLastOpened(logId: String, lastOpenedAtMillis: Long, nowMillis: Long): Int

    @Transaction
    suspend fun saveLogEntry(entity: ReadingLogEntity, syncFts: Boolean = true): Long {
        val existingDocId = if (entity.docId > 0L) entity.docId else getLogDocId(entity.id)
        val docId = if (existingDocId == null) {
            insertLogRaw(entity.copy(docId = 0L))
        } else {
            updateLogRaw(entity.copy(docId = existingDocId))
            existingDocId
        }
        if (syncFts) {
            val fts = entity.toFts(docId)
            deleteLogFtsByRowId(docId)
            insertLogFtsRaw(docId, fts.entityId, fts.title, fts.author, fts.series, fts.review)
        }
        return docId
    }

    @Transaction
    suspend fun deleteLogEntryById(id: String): Boolean {
        val docId = getLogDocId(id) ?: return false
        deleteLogFtsByRowId(docId)
        deleteLogRowById(id)
        return true
    }
}
