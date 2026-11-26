package com.example.frogreader.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.example.frogreader.data.search.SearchTextNormalizer

object DatabaseMigrations {
    // FTS text is derived data, but persisted normalization rules still need a versioned
    // rebuild. Any later normalizer change must add a migration and retain earlier paths.
    /**
     * v2 composes Unicode before normalizing text. Rebuild only the derived FTS rows inside
     * Room's migration transaction; content, immutable doc_ids, and user files stay intact.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            for (table in DatabaseSearchTables.all) {
                connection.execSQL("DELETE FROM ${table.fts}")
                val columns = table.columns.joinToString(", ")
                val placeholders = List(table.columns.size + 2) { "?" }.joinToString(", ")
                connection.prepare("SELECT doc_id, id, $columns FROM ${table.content}").use { source ->
                    connection.prepare(
                        "INSERT INTO ${table.fts}(rowid, entity_id, $columns) VALUES ($placeholders)",
                    ).use { destination ->
                        while (source.step()) {
                            destination.bindLong(1, source.getLong(0))
                            destination.bindText(2, source.getText(1))
                            table.columns.indices.forEach { index ->
                                val value = if (source.isNull(index + 2)) null else source.getText(index + 2)
                                destination.bindText(index + 3, SearchTextNormalizer.normalize(value))
                            }
                            destination.step()
                            destination.reset()
                            destination.clearBindings()
                        }
                    }
                }
            }
        }
    }
}
