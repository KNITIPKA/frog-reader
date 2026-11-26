package com.example.frogreader.data.search

import androidx.room3.withReadTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime

/** Observational timings on the host's actual bundled SQLite; no fragile timing thresholds. */
class SearchPerformanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `measure realistic SQLite search scales and selective passage loading`() = runBlocking {
        for (size in listOf(100, 1_000, 5_000)) {
            val root = temporary.newFolder("scale-$size")
            try {
                val books = (0 until size).map { i ->
                    val marker = if (i == size - 1) "needle" else "common"
                    Book(
                        id = "b$i", title = "Volume $i $marker", author = "Writer $i", format = BookFormat.EPUB, addedAtMillis = i.toLong(),
                        description = "Literature $marker وَالكتاب", status = ReadingStatus.FINISHED,
                        quotes = (0..1).map { q -> Quote("q$i-$q", "Passage $i $q $marker", 0, i.toLong()) },
                        bookmarks = listOf(Bookmark("m$i", i, 0, "Bookmark $i $marker", i.toLong())),
                    )
                }
                val logs = (0 until size).map { ReadingLogEntry(id = "l$it", title = "Logged Volume $it", review = "Review $it") }
                StoreFixture.seed(root, books, readingLog = logs)
                val db = DatabaseManager.getDatabase(root)
                val library = LibrarySearchRepository(root)
                val profile = ProfileSearchRepository(root)
                // Warm both Room generated statements and native FTS.
                library.searchBooks("needle", books)
                profile.search("needle", books, emptyList(), logs, ReadingStats())
                suspend fun medianMillis(block: suspend () -> Unit): Double =
                    List(5) { measureNanoTime { block() } / 1_000_000.0 }.sorted()[2]
                val libraryMs = medianMillis { assertEquals(1, library.searchBooks("needle", books).size) }
                val profileMs = medianMillis { assertEquals(2, profile.search("needle", books, emptyList(), logs, ReadingStats()).quotes.size) }
                val allRowsMs = medianMillis { db.withReadTransaction { assertEquals(size * 2, db.passagesDao().getAllQuotes().size) } }
                val candidatesMs = medianMillis { db.withReadTransaction { assertEquals(2, db.searchDao().getQuoteCandidates(listOf("q${size - 1}-0", "q${size - 1}-1")).size) } }
                val rapidQueriesMs = measureNanoTime {
                    for (query in listOf("n", "ne", "nee", "need", "needle", "n", "ne", "need")) library.searchBooks(query, books)
                } / 1_000_000.0
                val broadMs = measureNanoTime { assertEquals(size, library.searchBooks("v", books).size) } / 1_000_000.0
                val cliticMs = medianMillis { assertEquals(size, library.searchBooks("كتاب", books).size) }
                val runtime = Runtime.getRuntime()
                runtime.gc()
                val heapBefore = runtime.totalMemory() - runtime.freeMemory()
                val sampling = AtomicBoolean(true)
                val peakHeap = AtomicLong(heapBefore)
                val sampler = Thread {
                    while (sampling.get()) {
                        val used = runtime.totalMemory() - runtime.freeMemory()
                        peakHeap.updateAndGet { maxOf(it, used) }
                        Thread.sleep(1)
                    }
                }.apply { isDaemon = true; start() }
                lateinit var broadResults: ProfileSearchResults
                val profileBroadMs = try {
                    measureNanoTime { broadResults = profile.search("p", books, emptyList(), logs, ReadingStats()) } / 1_000_000.0
                } finally {
                    peakHeap.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
                    sampling.set(false)
                    sampler.join(1_000)
                }
                val profileBroadHeapDelta = (peakHeap.get() - heapBefore).coerceAtLeast(0L)
                assertEquals(size * 2, broadResults.quotes.size)
                println("SEARCH_BENCH size=$size quotes=${size * 2} bookmarks=$size logs=$size libraryRareMedianMs=$libraryMs profileRareMedianMs=$profileMs previousAllQuotesMedianMs=$allRowsMs candidateQuotesMedianMs=$candidatesMs rapid8QueriesMs=$rapidQueriesMs libraryBroadMs=$broadMs cliticMedianMs=$cliticMs profileBroadMs=$profileBroadMs profileBroadSampledHeapGrowthBytes=$profileBroadHeapDelta")
                BundledSQLiteDriver().open(DatabaseManager.databaseFileFor(root).absolutePath).use { connection ->
                    fun explain(sql: String, arg: String? = null): String = connection.prepare("EXPLAIN QUERY PLAN $sql").use { statement ->
                        if (arg != null) statement.bindText(1, arg)
                        buildList { while (statement.step()) add(statement.getText(3)) }.joinToString(" | ")
                    }
                    val ftsPlan = explain("SELECT entity_id FROM books_fts WHERE books_fts MATCH ?", "\"needle\"*")
                    val cliticPlan = explain("SELECT entity_id FROM books_fts WHERE instr(title, ?) > 0", "كتاب")
                    val idPlan = explain("SELECT * FROM quotes WHERE id IN (?)", "q${size - 1}-0")
                    val metricsPlan = explain("SELECT COUNT(*) FROM books WHERE status = 'FINISHED' AND id NOT IN (SELECT linked_book_id FROM reading_log WHERE status = 'FINISHED' AND linked_book_id IS NOT NULL)")
                    assertTrue(ftsPlan.contains("VIRTUAL TABLE INDEX"))
                    assertTrue(idPlan.contains("index_quotes_id"))
                    println("SEARCH_PLAN size=$size fts=$ftsPlan clitic=$cliticPlan candidates=$idPlan metrics=$metricsPlan")
                }
            } finally {
                DatabaseManager.closeForFilesRoot(root)
            }
        }
    }
}
