package com.example.frogreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

@Dao
interface BookDao {

    @Query("SELECT * FROM books")
    suspend fun getAllBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT doc_id FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookDocId(id: String): Long?

    @Query("SELECT id FROM books")
    suspend fun getAllBookIds(): List<String>

    @Query("SELECT * FROM book_progress")
    suspend fun getAllProgress(): List<BookProgressEntity>

    @Query("SELECT * FROM book_progress WHERE book_id = :bookId LIMIT 1")
    suspend fun getProgressByBookId(bookId: String): BookProgressEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBookRaw(entity: BookEntity): Long

    @Update
    suspend fun updateBookRaw(entity: BookEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProgressIgnore(entity: BookProgressEntity): Long

    @Update
    suspend fun updateProgressRaw(entity: BookProgressEntity)

    @Query("DELETE FROM book_progress WHERE book_id = :bookId")
    suspend fun deleteProgressByBookId(bookId: String)

    @Query("DELETE FROM books_fts WHERE rowid = :rowId")
    suspend fun deleteBookFtsByRowId(rowId: Long)

    @Query(
        """
        INSERT INTO books_fts(rowid, entity_id, title, author, series, description)
        VALUES (:rowId, :entityId, :title, :author, :series, :description)
        """
    )
    suspend fun insertBookFtsRaw(
        rowId: Long,
        entityId: String,
        title: String,
        author: String,
        series: String,
        description: String,
    )

    @Query("DELETE FROM books_fts")
    suspend fun clearAllBooksFts()

    @Query("DELETE FROM books")
    suspend fun clearAllBooks()

    @Query("DELETE FROM book_progress")
    suspend fun clearAllProgress()

    @Query(
        """
        UPDATE books
        SET reading_seconds = reading_seconds + :deltaSeconds,
            updated_at_millis = :nowMillis
        WHERE id = :bookId
        """
    )
    suspend fun addReadingSeconds(bookId: String, deltaSeconds: Long, nowMillis: Long): Int

    @Query(
        """
        UPDATE books
        SET last_opened_at_millis = :lastOpenedAtMillis,
            updated_at_millis = :nowMillis
        WHERE id = :bookId
        """
    )
    suspend fun updateLastOpened(bookId: String, lastOpenedAtMillis: Long, nowMillis: Long): Int

    @Query(
        """
        UPDATE books
        SET content_hash = :contentHash,
            size_bytes = :sizeBytes,
            updated_at_millis = :nowMillis
        WHERE id = :bookId
        """
    )
    suspend fun updateHashAndSize(bookId: String, contentHash: String, sizeBytes: Long, nowMillis: Long): Int

    @Query(
        """
        UPDATE books
        SET shelf_id = :shelfId,
            shelf_position = :shelfPosition,
            updated_at_millis = :nowMillis
        WHERE id = :bookId
        """
    )
    suspend fun updateBookShelfAssignment(
        bookId: String,
        shelfId: String?,
        shelfPosition: Int,
        nowMillis: Long,
    ): Int

    @Query("DELETE FROM quotes_fts WHERE rowid IN (SELECT doc_id FROM quotes WHERE book_id = :bookId)")
    suspend fun deleteQuotesFtsForBook(bookId: String)

    @Query("DELETE FROM bookmarks_fts WHERE rowid IN (SELECT doc_id FROM bookmarks WHERE book_id = :bookId)")
    suspend fun deleteBookmarksFtsForBook(bookId: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookRowById(bookId: String): Int

    suspend fun upsertProgress(entity: BookProgressEntity) {
        val inserted = insertProgressIgnore(entity)
        if (inserted == -1L) {
            updateProgressRaw(entity)
        }
    }

    suspend fun syncBookFts(entity: BookEntity, docId: Long) {
        val fts = entity.toFts(docId)
        deleteBookFtsByRowId(docId)
        insertBookFtsRaw(
            rowId = docId,
            entityId = fts.entityId,
            title = fts.title,
            author = fts.author,
            series = fts.series,
            description = fts.description,
        )
    }

    @Transaction
    suspend fun saveBookEntity(entity: BookEntity, syncFts: Boolean = true): Long {
        val existingDocId = if (entity.docId > 0L) entity.docId else getBookDocId(entity.id)
        val docId = if (existingDocId == null) {
            insertBookRaw(entity.copy(docId = 0L))
        } else {
            updateBookRaw(entity.copy(docId = existingDocId))
            existingDocId
        }
        if (syncFts) {
            syncBookFts(entity, docId)
        }
        return docId
    }

    @Transaction
    suspend fun deleteBookCascadeWithFts(bookId: String): Boolean {
        val docId = getBookDocId(bookId) ?: return false
        deleteQuotesFtsForBook(bookId)
        deleteBookmarksFtsForBook(bookId)
        deleteBookFtsByRowId(docId)
        deleteBookRowById(bookId)
        return true
    }
}
