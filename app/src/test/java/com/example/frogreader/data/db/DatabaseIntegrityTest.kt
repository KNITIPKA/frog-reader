package com.example.frogreader.data.db

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises Room and the actual bundled native SQLite, without mocking SQL or transactions. */
class DatabaseIntegrityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val roots = mutableListOf<File>()

    private fun newRoot(): File = temporary.newFolder().also { roots += it }

    @After
    fun closeDatabases() {
        roots.forEach(DatabaseManager::closeForFilesRoot)
    }

    private fun book(id: String, shelfId: String? = null) = BookEntity(
        id = id,
        title = "Title $id",
        format = "EPUB",
        addedAtMillis = 1L,
        status = "NONE",
        shelfId = shelfId,
    )

    private fun quote(bookId: String) = QuoteEntity(
        id = "quote-$bookId", bookId = bookId, text = "remembered passage",
        chapterIndex = 0, color = "DEFAULT", createdAtMillis = 1L,
    )

    private fun bookmark(bookId: String) = BookmarkEntity(
        id = "bookmark-$bookId", bookId = bookId, preview = "remembered place",
        chapterIndex = 0, flatIndex = 0, createdAtMillis = 1L,
    )

    private fun SQLiteConnection.longValue(sql: String): Long = prepare(sql).use {
        check(it.step()) { "No row: $sql" }
        it.getLong(0)
    }

    private fun <T> inspect(root: File, block: (SQLiteConnection) -> T): T =
        BundledSQLiteDriver().open(DatabaseManager.databaseFileFor(root).absolutePath).use(block)

    @Test
    fun `foreign keys and WAL are enabled on actual pooled connections`() = runBlocking {
        val root = newRoot()
        val db = DatabaseManager.getDatabase(root)
        db.bookDao().saveBookEntity(book("parent"))
        db.useWriterConnection { connection ->
            connection.usePrepared("PRAGMA foreign_keys") {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
            }
            connection.usePrepared("PRAGMA journal_mode") {
                assertTrue(it.step())
                assertEquals("wal", it.getText(0))
            }
        }
        // These cannot all share a connection: each reader waits until the others have opened.
        val ready = kotlinx.coroutines.channels.Channel<Unit>(3)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        withTimeout(20_000) { coroutineScope {
            val readers = (1..3).map {
                async(Dispatchers.IO) {
                    db.useReaderConnection { connection ->
                        val enabled = connection.usePrepared("PRAGMA foreign_keys") { statement ->
                            check(statement.step())
                            statement.getLong(0)
                        }
                        ready.send(Unit)
                        release.await()
                        enabled
                    }
                }
            }
            repeat(3) { ready.receive() }
            release.complete(Unit)
            assertEquals(listOf(1L, 1L, 1L), readers.awaitAll())
        } }
        assertTrue(runCatching { db.passagesDao().saveQuote(quote("missing")) }.isFailure)
        assertTrue(inspect(root) { it.longValue("SELECT count(*) FROM quotes") } == 0L)
        assertTrue(inspect(root) { it.longValue("SELECT count(*) FROM quotes_fts") } == 0L)
    }

    @Test
    fun `failed cascade rolls back content and all FTS rows then successful delete preserves reading history`() = runBlocking {
        val root = newRoot()
        val db = DatabaseManager.getDatabase(root)
        db.shelfDao().saveShelf(ShelfEntity(id = "shelf", createdAtMillis = 1L))
        db.bookDao().saveBookEntity(book("parent", shelfId = "shelf"))
        db.bookDao().upsertProgress(BookProgressEntity(bookId = "parent", fraction = 0.5f))
        db.passagesDao().saveQuote(quote("parent"))
        db.passagesDao().saveBookmark(bookmark("parent"))
        db.readingListsDao().saveQueueEntry(ReadingQueueEntity(id = "queue", title = "queued", linkedBookId = "parent"))
        db.readingListsDao().saveLogEntry(ReadingLogEntity(
            id = "log", title = "history", source = "FROGREADER", status = "FINISHED", linkedBookId = "parent",
        ))
        db.useWriterConnection {
            it.executeSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON books BEGIN SELECT RAISE(ABORT, 'fault'); END")
        }
        assertTrue(runCatching { db.bookDao().deleteBookCascadeWithFts("parent") }.isFailure)
        for (table in listOf("books", "book_progress", "quotes", "bookmarks", "books_fts", "quotes_fts", "bookmarks_fts")) {
            assertEquals(table, 1L, inspect(root) { it.longValue("SELECT count(*) FROM $table") })
        }
        assertEquals("parent", db.readingListsDao().getQueueEntryById("queue")?.linkedBookId)

        db.useWriterConnection { it.executeSQL("DROP TRIGGER fail_delete") }
        assertTrue(db.bookDao().deleteBookCascadeWithFts("parent"))
        for (table in listOf("books", "book_progress", "quotes", "bookmarks", "books_fts", "quotes_fts", "bookmarks_fts")) {
            assertEquals(table, 0L, inspect(root) { it.longValue("SELECT count(*) FROM $table") })
        }
        assertNull(db.readingListsDao().getQueueEntryById("queue")?.linkedBookId)
        assertEquals("parent", db.readingListsDao().getLogEntryById("log")?.linkedBookId)
        assertEquals(1L, inspect(root) { it.longValue("SELECT count(*) FROM reading_log_fts WHERE reading_log_fts MATCH 'history'") })
        assertFalse(db.bookDao().deleteBookCascadeWithFts("parent"))
        inspect(root) { connection ->
            connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) }
        }
    }

    @Test
    fun `shelf deletion unassigns books without deleting their progress or passages`() = runBlocking {
        val root = newRoot()
        val db = DatabaseManager.getDatabase(root)
        db.shelfDao().saveShelf(ShelfEntity(id = "shelf", createdAtMillis = 1L))
        db.bookDao().saveBookEntity(book("parent", shelfId = "shelf"))
        db.bookDao().upsertProgress(BookProgressEntity(bookId = "parent", fraction = 0.4f))
        db.passagesDao().saveQuote(quote("parent"))
        db.shelfDao().deleteShelfById("shelf")
        assertNull(db.bookDao().getBookById("parent")?.shelfId)
        assertEquals(0.4f, db.bookDao().getProgressByBookId("parent")?.fraction)
        assertEquals(1, db.passagesDao().getQuotesForBook("parent").size)
    }

    @Test
    fun `concurrent stats transactions preserve every increment and latest read time`() = runBlocking {
        val db = DatabaseManager.getDatabase(newRoot())
        coroutineScope {
            (1..100).map { value ->
                async(Dispatchers.IO) {
                    db.statsAndAchievementsDao().addReadingSecondsForDate("2026-10-29", 1L, value.toLong())
                }
            }.awaitAll()
        }
        assertEquals(100L, db.statsAndAchievementsDao().getDailyStat("2026-10-29")?.seconds)
        assertEquals(100L, db.statsAndAchievementsDao().getStatsMeta()?.lastReadAtMillis)
    }

    @Test
    fun `doc ids and FTS row joins survive deletion update vacuum and reopen`() = runBlocking {
        val root = newRoot()
        var db = DatabaseManager.getDatabase(root)
        val deletedId = db.bookDao().saveBookEntity(book("deleted"))
        val keptId = db.bookDao().saveBookEntity(book("kept"))
        db.passagesDao().saveQuote(quote("kept"))
        val quoteId = db.passagesDao().getQuoteDocId("quote-kept")
        db.bookDao().deleteBookCascadeWithFts("deleted")
        assertEquals(keptId, db.bookDao().saveBookEntity(book("kept").copy(title = "Updated title")))
        db.useWriterConnection { it.executeSQL("VACUUM") }
        DatabaseManager.closeForFilesRoot(root)
        db = DatabaseManager.getDatabase(root)
        assertEquals(keptId, db.bookDao().getBookDocId("kept"))
        assertEquals(quoteId, db.passagesDao().getQuoteDocId("quote-kept"))
        assertTrue(db.bookDao().saveBookEntity(book("next")) > maxOf(deletedId, keptId))
        assertEquals(1L, inspect(root) {
            it.longValue("SELECT count(*) FROM books_fts f JOIN books b ON b.doc_id = f.rowid AND b.id = f.entity_id WHERE books_fts MATCH 'updated'")
        })
    }

    @Test
    fun `unsupported database version fails without destructive fallback`() = runBlocking {
        val root = newRoot()
        DatabaseManager.getDatabase(root).bookDao().saveBookEntity(book("preserved"))
        DatabaseManager.closeForFilesRoot(root)
        inspect(root) { it.execSQL("PRAGMA user_version = 3") }
        val failure = runCatching { DatabaseManager.getDatabase(root).bookDao().getAllBooks() }.exceptionOrNull()
        assertNotNull("Opening an unknown version must fail instead of deleting data", failure)
        DatabaseManager.closeForFilesRoot(root)
        inspect(root) {
            assertEquals(3L, it.longValue("PRAGMA user_version"))
            assertEquals(1L, it.longValue("SELECT count(*) FROM books WHERE id = 'preserved'"))
            assertEquals(1L, it.longValue("SELECT count(*) FROM books_fts WHERE books_fts MATCH 'preserved'"))
        }
    }

    @Test
    fun `exported version one schema can create a Room compatible fixture for future migrations`() = runBlocking {
        val root = newRoot()
        val dbFile = DatabaseManager.databaseFileFor(root)
        check(dbFile.parentFile!!.mkdirs())
        val schemaFile = File("schemas/com.example.frogreader.data.db.FrogReaderDatabase/1.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        inspect(root) { connection ->
            for (element in schema.getValue("entities").jsonArray) {
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity["indices"]?.jsonArray.orEmpty()) {
                    connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
            connection.execSQL("PRAGMA user_version = 1")
        }
        val db = DatabaseManager.getDatabase(root)
        db.bookDao().saveBookEntity(book("schema-fixture"))
        assertEquals("Title schema-fixture", db.bookDao().getBookById("schema-fixture")?.title)
        assertEquals(2L, inspect(root) { it.longValue("PRAGMA user_version") })
    }

    @Test
    fun `version one normalization migration preserves all source rows and stable FTS mappings`() = runBlocking {
        val root = newRoot()
        var db = DatabaseManager.getDatabase(root)
        val original = "І\u0308жа и\u0306 мрії"
        val bookId = db.bookDao().saveBookEntity(book("preserved").copy(title = original))
        val quoteId = db.passagesDao().saveQuote(quote("preserved").copy(text = original))
        val bookmarkId = db.passagesDao().saveBookmark(bookmark("preserved").copy(preview = original))
        val queueId = db.readingListsDao().saveQueueEntry(ReadingQueueEntity(id = "queue", title = original, linkedBookId = "preserved"))
        val logId = db.readingListsDao().saveLogEntry(ReadingLogEntity(
            id = "log", title = original, source = "FROGREADER", status = "FINISHED", linkedBookId = "preserved",
        ))
        db.bookDao().upsertProgress(BookProgressEntity(bookId = "preserved", fraction = 0.6f))
        db.statsAndAchievementsDao().addReadingSecondsForDate("2026-10-29", 123L, 99L)
        DatabaseManager.closeForFilesRoot(root)
        inspect(root) { connection ->
            // v1 and v2 share table layouts. Reproduce v1's derived decomposed-Unicode text,
            // while leaving the exact source data and every stable integer key untouched.
            for (table in DatabaseSearchTables.all) {
                connection.execSQL("UPDATE ${table.fts} SET ${table.columns.first()} = 'іжа и мріі'")
            }
            connection.execSQL("PRAGMA user_version = 1")
        }
        db = DatabaseManager.getDatabase(root)
        assertEquals(original, db.bookDao().getBookById("preserved")?.title)
        assertEquals(bookId, db.bookDao().getBookDocId("preserved"))
        assertEquals(quoteId, db.passagesDao().getQuoteDocId("quote-preserved"))
        assertEquals(bookmarkId, db.passagesDao().getBookmarkDocId("bookmark-preserved"))
        assertEquals(queueId, db.readingListsDao().getQueueDocId("queue"))
        assertEquals(logId, db.readingListsDao().getLogDocId("log"))
        assertEquals(0.6f, db.bookDao().getProgressByBookId("preserved")?.fraction)
        assertEquals(123L, db.statsAndAchievementsDao().getDailyStat("2026-10-29")?.seconds)
        inspect(root) { connection ->
            assertEquals(2L, connection.longValue("PRAGMA user_version"))
            DatabaseIntegrityChecks.validateSearchIndexes(connection)
            for (table in DatabaseSearchTables.all) {
                assertEquals(table.fts, 1L, connection.longValue("SELECT count(*) FROM ${table.fts} WHERE ${table.fts} MATCH 'їжа'"))
            }
        }
    }

    @Test
    fun `integrity validation rejects stale missing orphaned and mismatched FTS rows`() = runBlocking {
        for (mutation in listOf(
            "UPDATE books_fts SET title = 'stale'",
            "DELETE FROM books_fts",
            "INSERT INTO books_fts(rowid, entity_id, title, author, series, description) VALUES(999, 'orphan', 'orphan', '', '', '')",
            "UPDATE books_fts SET entity_id = 'wrong'",
            "UPDATE books_fts_data SET block = x'00' WHERE id > 10",
        )) {
            val root = newRoot()
            DatabaseManager.getDatabase(root).bookDao().saveBookEntity(book("preserved"))
            DatabaseManager.closeForFilesRoot(root)
            inspect(root) { connection ->
                DatabaseIntegrityChecks.validateSearchIndexes(connection)
                connection.execSQL(mutation)
                assertTrue(mutation, runCatching { DatabaseIntegrityChecks.validateSearchIndexes(connection) }.isFailure)
                assertEquals(1L, connection.longValue("SELECT count(*) FROM books WHERE id = 'preserved'"))
            }
        }
    }

    @Test
    fun `failed normalization migration rolls back earlier index writes and preserves source data`() = runBlocking {
        val root = newRoot()
        DatabaseManager.getDatabase(root).bookDao().saveBookEntity(book("preserved"))
        DatabaseManager.closeForFilesRoot(root)
        inspect(root) { connection ->
            connection.execSQL("UPDATE books_fts SET title = 'original old index'")
            // Force a failure after the migration has already rebuilt the preceding FTS tables.
            connection.execSQL("DROP TABLE reading_log_fts")
            connection.execSQL("PRAGMA user_version = 1")
        }
        assertTrue(runCatching { DatabaseManager.getDatabase(root).bookDao().getAllBooks() }.isFailure)
        DatabaseManager.closeForFilesRoot(root)
        inspect(root) { connection ->
            assertEquals(1L, connection.longValue("PRAGMA user_version"))
            assertEquals(1L, connection.longValue("SELECT count(*) FROM books WHERE id = 'preserved'"))
            connection.prepare("SELECT title FROM books_fts").use {
                assertTrue(it.step())
                assertEquals("original old index", it.getText(0))
            }
        }
    }

    @Test
    fun `database JSON mapper distinguishes absent optional values from invalid present payloads`() {
        assertNull(DbJson.decodeReaderSettings(null))
        assertNull(DbJson.decodeDocumentLocation(null))
        assertNull(DbJson.decodePrivateAttachment(null))
        assertEquals(emptyList<String>(), DbJson.decodeStringList("[]"))
        assertEquals(emptyMap<String, String>(), DbJson.decodeStringMap("{}"))
        assertEquals(emptyList<com.example.frogreader.data.model.PdfTextBounds>(), DbJson.decodePdfBounds("[]"))
        for (raw in listOf("", " ", "{broken", "[]")) {
            assertTrue(runCatching { DbJson.decodeReaderSettings(raw) }.isFailure)
            assertTrue(runCatching { DbJson.decodePrivateAttachment(raw) }.isFailure)
            assertTrue(runCatching { DbJson.decodeDocumentLocation(raw) }.isFailure)
        }
        assertTrue(runCatching { readDatabaseEnum<com.example.frogreader.data.model.BookFormat>("UNKNOWN") }.isFailure)
    }
}
