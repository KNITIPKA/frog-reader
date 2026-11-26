package com.example.frogreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface PassagesDao {

    @Query("SELECT * FROM quotes ORDER BY created_at_millis ASC, doc_id ASC")
    suspend fun getAllQuotes(): List<QuoteEntity>

    @Query("SELECT * FROM quotes WHERE book_id = :bookId ORDER BY created_at_millis ASC, doc_id ASC")
    suspend fun getQuotesForBook(bookId: String): List<QuoteEntity>

    @Query("SELECT * FROM quotes WHERE id = :id LIMIT 1")
    suspend fun getQuoteById(id: String): QuoteEntity?

    @Query("SELECT doc_id FROM quotes WHERE id = :id LIMIT 1")
    suspend fun getQuoteDocId(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertQuoteRaw(entity: QuoteEntity): Long

    @Update
    suspend fun updateQuoteRaw(entity: QuoteEntity)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun deleteQuoteRowById(id: String): Int

    @Query("DELETE FROM quotes_fts WHERE rowid = :rowId")
    suspend fun deleteQuoteFtsByRowId(rowId: Long)

    @Query(
        """
        INSERT INTO quotes_fts(rowid, entity_id, text, note)
        VALUES (:rowId, :entityId, :text, :note)
        """
    )
    suspend fun insertQuoteFtsRaw(rowId: Long, entityId: String, text: String, note: String)

    @Query("DELETE FROM quotes_fts")
    suspend fun clearAllQuotesFts()

    @Query("DELETE FROM quotes")
    suspend fun clearAllQuotes()

    @Query("SELECT * FROM bookmarks ORDER BY flat_index ASC, doc_id ASC")
    suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE book_id = :bookId ORDER BY flat_index ASC, doc_id ASC")
    suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: String): BookmarkEntity?

    @Query("SELECT doc_id FROM bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkDocId(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookmarkRaw(entity: BookmarkEntity): Long

    @Update
    suspend fun updateBookmarkRaw(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkRowById(id: String): Int

    @Query("DELETE FROM bookmarks_fts WHERE rowid = :rowId")
    suspend fun deleteBookmarkFtsByRowId(rowId: Long)

    @Query(
        """
        INSERT INTO bookmarks_fts(rowid, entity_id, preview)
        VALUES (:rowId, :entityId, :preview)
        """
    )
    suspend fun insertBookmarkFtsRaw(rowId: Long, entityId: String, preview: String)

    @Query("DELETE FROM bookmarks_fts")
    suspend fun clearAllBookmarksFts()

    @Query("DELETE FROM bookmarks")
    suspend fun clearAllBookmarks()

    @Transaction
    suspend fun saveQuote(entity: QuoteEntity): Long {
        val existingDocId = if (entity.docId > 0L) entity.docId else getQuoteDocId(entity.id)
        val docId = if (existingDocId == null) {
            insertQuoteRaw(entity.copy(docId = 0L))
        } else {
            updateQuoteRaw(entity.copy(docId = existingDocId))
            existingDocId
        }
        val fts = entity.toFts(docId)
        deleteQuoteFtsByRowId(docId)
        insertQuoteFtsRaw(docId, fts.entityId, fts.text, fts.note)
        return docId
    }

    @Transaction
    suspend fun deleteQuoteById(id: String): Boolean {
        val docId = getQuoteDocId(id) ?: return false
        deleteQuoteFtsByRowId(docId)
        deleteQuoteRowById(id)
        return true
    }

    @Transaction
    suspend fun saveBookmark(entity: BookmarkEntity): Long {
        val existingDocId = if (entity.docId > 0L) entity.docId else getBookmarkDocId(entity.id)
        val docId = if (existingDocId == null) {
            insertBookmarkRaw(entity.copy(docId = 0L))
        } else {
            updateBookmarkRaw(entity.copy(docId = existingDocId))
            existingDocId
        }
        val fts = entity.toFts(docId)
        deleteBookmarkFtsByRowId(docId)
        insertBookmarkFtsRaw(docId, fts.entityId, fts.preview)
        return docId
    }

    @Transaction
    suspend fun deleteBookmarkById(id: String): Boolean {
        val docId = getBookmarkDocId(id) ?: return false
        deleteBookmarkFtsByRowId(docId)
        deleteBookmarkRowById(id)
        return true
    }
}
