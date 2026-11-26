package com.example.frogreader.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingStatus
import com.example.frogreader.data.search.SearchTextNormalizer
import java.io.IOException

/** Shared FTS layout for validation and the non-destructive search-normalization migration. */
internal object DatabaseSearchTables {
    data class Table(val content: String, val fts: String, val columns: List<String>)

    val all = listOf(
        Table("books", "books_fts", listOf("title", "author", "series", "description")),
        Table("quotes", "quotes_fts", listOf("text", "note")),
        Table("bookmarks", "bookmarks_fts", listOf("preview")),
        Table("reading_queue", "reading_queue_fts", listOf("title", "author", "note")),
        Table("reading_log", "reading_log_fts", listOf("title", "author", "series", "review")),
    )
}

internal object DatabaseIntegrityChecks {
    /** SQLite structural checks cannot recognize invalid JSON payloads or unsupported enums. */
    fun validateContent(connection: SQLiteConnection) {
        validateRows(connection, "books", "id, format, status, reader_settings_json, genres_json, translators_json, additional_metadata_json") {
            readDatabaseEnum<BookFormat>(it.getText(1))
            readDatabaseEnum<ReadingStatus>(it.getText(2))
            DbJson.decodeReaderSettings(it.textOrNull(3))
            DbJson.decodeStringList(it.getText(4))
            DbJson.decodeStringList(it.getText(5))
            DbJson.decodeStringMap(it.getText(6))
        }
        validateRows(connection, "book_progress", "book_id, document_location_json") {
            DbJson.decodeDocumentLocation(it.textOrNull(1))
        }
        validateRows(connection, "quotes", "id, color, document_location_json, pdf_selection_json") {
            readDatabaseEnum<QuoteColor>(it.getText(1))
            DbJson.decodeDocumentLocation(it.textOrNull(2))
            DbJson.decodePdfBounds(it.getText(3))
        }
        validateRows(connection, "bookmarks", "id, document_location_json") {
            DbJson.decodeDocumentLocation(it.textOrNull(1))
        }
        validateRows(connection, "reading_queue", "id, private_attachment_json") {
            DbJson.decodePrivateAttachment(it.textOrNull(1))
        }
        validateRows(connection, "reading_log", "id, source, status, book_format") {
            readDatabaseEnum<ReadingLogSource>(it.getText(1))
            readDatabaseEnum<ReadingLogStatus>(it.getText(2))
            it.textOrNull(3)?.let { format -> readDatabaseEnum<BookFormat>(format) }
        }
    }

    private fun SQLiteStatement.textOrNull(index: Int): String? = if (isNull(index)) null else getText(index)

    private inline fun validateRows(
        connection: SQLiteConnection,
        table: String,
        columns: String,
        validate: (SQLiteStatement) -> Unit,
    ) {
        connection.prepare("SELECT $columns FROM $table").use { statement ->
            while (statement.step()) {
                try {
                    validate(statement)
                } catch (error: Exception) {
                    throw IOException("Invalid persisted content in $table for id=${statement.getText(0)}.", error)
                }
            }
        }
    }

    /**
     * Runs on a private, current-schema staged snapshot before activation. quick_check alone
     * does not validate FTS postings, parent row correspondence, or stale normalized text.
     */
    fun validateSearchIndexes(connection: SQLiteConnection) {
        for (table in DatabaseSearchTables.all) {
            try {
                connection.execSQL("INSERT INTO ${table.fts}(${table.fts}) VALUES('integrity-check')")
            } catch (error: Exception) {
                throw IOException("FTS integrity check failed for ${table.fts}.", error)
            }
            connection.prepare(
                "SELECT 1 FROM ${table.fts} f LEFT JOIN ${table.content} c ON c.doc_id = f.rowid " +
                    "WHERE c.doc_id IS NULL LIMIT 1",
            ).use {
                if (it.step()) throw IOException("FTS index ${table.fts} contains an orphaned row.")
            }
            val fields = table.columns.joinToString(", ") { "c.$it, f.$it" }
            connection.prepare(
                "SELECT c.doc_id, c.id, f.rowid, f.entity_id, $fields " +
                    "FROM ${table.content} c LEFT JOIN ${table.fts} f ON f.rowid = c.doc_id",
            ).use { statement ->
                while (statement.step()) {
                    val docId = statement.getLong(0)
                    if (statement.isNull(2) || statement.isNull(3) || statement.getText(1) != statement.getText(3)) {
                        throw IOException("FTS index ${table.fts} has an invalid mapping for doc_id=$docId.")
                    }
                    table.columns.forEachIndexed { index, column ->
                        val sourceIndex = 4 + index * 2
                        val source = if (statement.isNull(sourceIndex)) null else statement.getText(sourceIndex)
                        val normalized = SearchTextNormalizer.normalize(source)
                        if (statement.isNull(sourceIndex + 1) || normalized != statement.getText(sourceIndex + 1)) {
                            throw IOException("FTS index ${table.fts} has stale $column for doc_id=$docId.")
                        }
                    }
                }
            }
        }
    }
}
