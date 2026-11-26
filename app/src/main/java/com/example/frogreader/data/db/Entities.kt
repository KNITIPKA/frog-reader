package com.example.frogreader.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "shelves",
    indices = [
        Index(value = ["sort_key"]),
    ],
)
data class ShelfEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String = "",
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "sort_key")
    val sortKey: Long = 0L,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

/**
 * Primary library book record.
 *
 * [docId] (`INTEGER PRIMARY KEY AUTOINCREMENT`) provides an immutable integer row key that
 * SQLite's `VACUUM` and `VACUUM INTO` never rewrite, keeping [BookFtsEntity] `rowid` joins stable.
 * [id] (`TEXT NOT NULL UNIQUE`) remains the stable UUID exposed across the domain and UI layers.
 */
@Entity(
    tableName = "books",
    foreignKeys = [
        ForeignKey(
            entity = ShelfEntity::class,
            parentColumns = ["id"],
            childColumns = ["shelf_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["shelf_id", "shelf_position"]),
        Index(value = ["content_hash"]),
        Index(value = ["status"]),
        Index(value = ["last_opened_at_millis"]),
        Index(value = ["added_at_millis"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id")
    val docId: Long = 0L,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String? = null,
    @ColumnInfo(name = "format")
    val format: String,
    @ColumnInfo(name = "file_name")
    val fileName: String? = null,
    @ColumnInfo(name = "cover_file_name")
    val coverFileName: String? = null,
    @ColumnInfo(name = "added_at_millis")
    val addedAtMillis: Long,
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0L,
    @ColumnInfo(name = "last_opened_at_millis")
    val lastOpenedAtMillis: Long? = null,
    @ColumnInfo(name = "started_at_millis")
    val startedAtMillis: Long? = null,
    @ColumnInfo(name = "finished_at_millis")
    val finishedAtMillis: Long? = null,
    @ColumnInfo(name = "reading_seconds")
    val readingSeconds: Long = 0L,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "rating")
    val rating: Int? = null,
    @ColumnInfo(name = "rating_half_stars")
    val ratingHalfStars: Int? = null,
    @ColumnInfo(name = "active_reading_log_id")
    val activeReadingLogId: String? = null,
    @ColumnInfo(name = "suppress_reading_log")
    val suppressReadingLog: Boolean = false,
    @ColumnInfo(name = "review")
    val review: String? = null,
    @ColumnInfo(name = "review_updated_at_millis")
    val reviewUpdatedAtMillis: Long? = null,
    @ColumnInfo(name = "reading_queue_note")
    val readingQueueNote: String? = null,
    @ColumnInfo(name = "reader_settings_json")
    val readerSettingsJson: String? = null,
    @ColumnInfo(name = "genres_json")
    val genresJson: String = "[]",
    @ColumnInfo(name = "series")
    val series: String? = null,
    @ColumnInfo(name = "series_number")
    val seriesNumber: Float? = null,
    @ColumnInfo(name = "publisher")
    val publisher: String? = null,
    @ColumnInfo(name = "year")
    val year: String? = null,
    @ColumnInfo(name = "isbn")
    val isbn: String? = null,
    @ColumnInfo(name = "translators_json")
    val translatorsJson: String = "[]",
    @ColumnInfo(name = "description")
    val description: String? = null,
    @ColumnInfo(name = "language")
    val language: String? = null,
    @ColumnInfo(name = "generate_pdf_cover")
    val generatePdfCover: Boolean = true,
    @ColumnInfo(name = "additional_metadata_json")
    val additionalMetadataJson: String = "{}",
    @ColumnInfo(name = "pdf_annotation_fingerprint")
    val pdfAnnotationFingerprint: String? = null,
    @ColumnInfo(name = "shelf_id")
    val shelfId: String? = null,
    @ColumnInfo(name = "shelf_position")
    val shelfPosition: Int = 0,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

/**
 * High-frequency reading position row isolated from [BookEntity] so page turns update a tiny row
 * without rewriting book descriptions, metadata, or FTS5 search indexes.
 */
@Entity(
    tableName = "book_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
)
data class BookProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int = 0,
    @ColumnInfo(name = "element_index")
    val elementIndex: Int = 0,
    @ColumnInfo(name = "scroll_offset")
    val scrollOffset: Int = 0,
    @ColumnInfo(name = "fraction")
    val fraction: Float = 0f,
    @ColumnInfo(name = "pages_left_in_chapter")
    val pagesLeftInChapter: Int = -1,
    @ColumnInfo(name = "total_pages")
    val totalPages: Int = 0,
    @ColumnInfo(name = "document_location_json")
    val documentLocationJson: String? = null,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

@Entity(
    tableName = "quotes",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["book_id", "created_at_millis"]),
        Index(value = ["created_at_millis"]),
    ],
)
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id")
    val docId: Long = 0L,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "start_item")
    val startItem: Int = -1,
    @ColumnInfo(name = "start_char")
    val startChar: Int = -1,
    @ColumnInfo(name = "end_item")
    val endItem: Int = -1,
    @ColumnInfo(name = "end_char")
    val endChar: Int = -1,
    @ColumnInfo(name = "color")
    val color: String,
    @ColumnInfo(name = "document_location_json")
    val documentLocationJson: String? = null,
    @ColumnInfo(name = "pdf_selection_json")
    val pdfSelectionJson: String = "[]",
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["book_id", "created_at_millis"]),
        Index(value = ["created_at_millis"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id")
    val docId: Long = 0L,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "flat_index")
    val flatIndex: Int,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "preview")
    val preview: String,
    @ColumnInfo(name = "orphaned")
    val orphaned: Boolean = false,
    @ColumnInfo(name = "document_location_json")
    val documentLocationJson: String? = null,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

@Entity(
    tableName = "reading_queue",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["linked_book_id"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["priority_order"]),
        Index(value = ["linked_book_id"]),
    ],
)
data class ReadingQueueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id")
    val docId: Long = 0L,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String = "",
    @ColumnInfo(name = "note")
    val note: String = "",
    @ColumnInfo(name = "priority_order")
    val priorityOrder: Int = 0,
    @ColumnInfo(name = "linked_book_id")
    val linkedBookId: String? = null,
    @ColumnInfo(name = "private_attachment_json")
    val privateAttachmentJson: String? = null,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
)

/**
 * Durable record of a reading in the user's Reading Log.
 *
 * [linkedBookId] is intentionally an indexed lineage identifier rather than a foreign key with
 * `ON DELETE SET NULL`: when a library book is deleted, past readings of that book continue to
 * share their original `linkedBookId` so the details screen can group multiple readings together.
 */
@Entity(
    tableName = "reading_log",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["manual_order"]),
        Index(value = ["linked_book_id"]),
        Index(value = ["status"]),
    ],
)
data class ReadingLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id")
    val docId: Long = 0L,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "manual_order")
    val manualOrder: Int = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String = "",
    @ColumnInfo(name = "series")
    val series: String = "",
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "started_year")
    val startedYear: Int? = null,
    @ColumnInfo(name = "started_month")
    val startedMonth: Int? = null,
    @ColumnInfo(name = "started_day")
    val startedDay: Int? = null,
    @ColumnInfo(name = "finished_year")
    val finishedYear: Int? = null,
    @ColumnInfo(name = "finished_month")
    val finishedMonth: Int? = null,
    @ColumnInfo(name = "finished_day")
    val finishedDay: Int? = null,
    @ColumnInfo(name = "linked_book_id")
    val linkedBookId: String? = null,
    @ColumnInfo(name = "cover_file_name")
    val coverFileName: String? = null,
    @ColumnInfo(name = "reading_seconds")
    val readingSeconds: Long = 0L,
    @ColumnInfo(name = "last_opened_at_millis")
    val lastOpenedAtMillis: Long? = null,
    @ColumnInfo(name = "rating_half_stars")
    val ratingHalfStars: Int? = null,
    @ColumnInfo(name = "review")
    val review: String = "",
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long = 0L,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long = 0L,
    @ColumnInfo(name = "finished_at_millis")
    val finishedAtMillis: Long? = null,
    @ColumnInfo(name = "book_format")
    val bookFormat: String? = null,
    @ColumnInfo(name = "started_order_year")
    val startedOrderYear: Int? = null,
    @ColumnInfo(name = "started_order_month")
    val startedOrderMonth: Int? = null,
    @ColumnInfo(name = "started_order_day")
    val startedOrderDay: Int? = null,
    @ColumnInfo(name = "started_order_rank")
    val startedOrderRank: Int? = null,
    @ColumnInfo(name = "finished_order_year")
    val finishedOrderYear: Int? = null,
    @ColumnInfo(name = "finished_order_month")
    val finishedOrderMonth: Int? = null,
    @ColumnInfo(name = "finished_order_day")
    val finishedOrderDay: Int? = null,
    @ColumnInfo(name = "finished_order_rank")
    val finishedOrderRank: Int? = null,
    @ColumnInfo(name = "progress_fraction")
    val progressFraction: Float? = null,
)

@Entity(tableName = "daily_reading_stats")
data class DailyReadingStatEntity(
    @PrimaryKey
    @ColumnInfo(name = "iso_date")
    val isoDate: String,
    @ColumnInfo(name = "seconds")
    val seconds: Long,
)

@Entity(tableName = "reading_stats_meta")
data class ReadingStatsMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = 1,
    @ColumnInfo(name = "last_read_at_millis")
    val lastReadAtMillis: Long? = null,
)

@Entity(tableName = "achievement_progress")
data class AchievementProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "achievement_id")
    val achievementId: String,
    @ColumnInfo(name = "current_value")
    val currentValue: Long = 0L,
    @ColumnInfo(name = "unlocked")
    val unlocked: Boolean = false,
    @ColumnInfo(name = "unlocked_at_millis")
    val unlockedAtMillis: Long? = null,
)

// ---------------------------------------------------------------------------
// FTS5 Search Virtual Tables (rowid == parent entity doc_id)
// ---------------------------------------------------------------------------

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "0"],
    prefix = [2, 3],
    notIndexed = ["entity_id"],
)
@Entity(tableName = "books_fts")
data class BookFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String,
    @ColumnInfo(name = "series")
    val series: String,
    @ColumnInfo(name = "description")
    val description: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "0"],
    prefix = [2, 3],
    notIndexed = ["entity_id"],
)
@Entity(tableName = "quotes_fts")
data class QuoteFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "note")
    val note: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "0"],
    prefix = [2, 3],
    notIndexed = ["entity_id"],
)
@Entity(tableName = "bookmarks_fts")
data class BookmarkFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "preview")
    val preview: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "0"],
    prefix = [2, 3],
    notIndexed = ["entity_id"],
)
@Entity(tableName = "reading_queue_fts")
data class ReadingQueueFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String,
    @ColumnInfo(name = "note")
    val note: String,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics", "0"],
    prefix = [2, 3],
    notIndexed = ["entity_id"],
)
@Entity(tableName = "reading_log_fts")
data class ReadingLogFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0L,
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "author")
    val author: String,
    @ColumnInfo(name = "series")
    val series: String,
    @ColumnInfo(name = "review")
    val review: String,
)
