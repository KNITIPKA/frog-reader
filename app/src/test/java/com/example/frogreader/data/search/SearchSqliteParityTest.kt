package com.example.frogreader.data.search

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Exercises the real Room + bundled SQLite FTS5 path, not an in-memory stand-in. */
class SearchSqliteParityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root

    @After fun closeDatabase() = DatabaseManager.closeForFilesRoot(root)

    @Test fun `SQLite candidates Kotlin verification and UTF16 highlights agree across scripts`() = runTest {
        val texts = listOf(
            "Time", "Timeless café", "Sometimes", "Café naïve Straße Œuvre",
            "Пам’ять солов'їний ґанок єдність", "Їжак йорж Київ",
            "І\u0308жак и\u0306орж Киі\u0308в", "Іжак иорж Киів", "вмостилася Сапієнс",
            "وَالكِتَابُ بِالقَلَم", "كتاب قلم", "كتابات", "مكتبة", "وּבַסֵּפֶר שֶׁלִּי",
            "ספר", "תנ״ך רמב\"ם", "𐐀𐐁 alpha 😀beta", "co-operate foo_bar AND OR",
            "e\u0301lan", "ї\u0301жак", "LatinКирилиця", "foo\u00a0bar", "quote \" test", "\uE000Time",
        )
        val books = texts.mapIndexed { index, text ->
            Book(
                id = "b$index", title = text, author = "Writer $index", format = BookFormat.EPUB, addedAtMillis = index.toLong(),
                quotes = listOf(Quote("q$index", text, 0, index.toLong())),
                bookmarks = listOf(Bookmark("m$index", index, 0, text, index.toLong())),
            )
        }
        StoreFixture.seed(root, books)
        val library = LibrarySearchRepository(root)
        val profile = ProfileSearchRepository(root)
        val queries = listOf(
            "time", "tim", "cafe", "naïve strasse", "oeu", "пам'ять", "солов’ї",
            "ґан", "єд", "їж", "і\u0308ж", "йор", "и\u0306ор", "іж", "київ", "с",
            "كتاب", "كتاب قلم", "قلم", "تاب", "ספר", "תנ\"ך", "רמב״ם",
            "𐐨", "alpha beta", "co oper", "foo bar", "AND OR*", "él", "кири", "quote \" test",
            "--- *** ()", "absent", "foo OR \"missing\"",
        )
        for (query in queries) {
            val tokens = SearchTextNormalizer.tokenize(query)
            val expected = books.filter {
                SearchTextNormalizer.matchesAllTokens(listOf(SearchTextNormalizer.normalize(it.title)), tokens)
            }.map { it.id }.toSet()
            val actual = library.searchBooks(query, books).map { it.book.id }.toSet()
            assertEquals("Library SQLite/Kotlin parity for '$query'", expected, actual)
            val hits = profile.search(query, books, emptyList(), emptyList(), ReadingStats())
            assertEquals("Quote SQLite/Kotlin parity for '$query'", expected.map { it.replaceFirst('b', 'q') }.toSet(), hits.quotes.map { it.quoteId }.toSet())
            assertEquals("Bookmark SQLite/Kotlin parity for '$query'", expected.map { it.replaceFirst('b', 'm') }.toSet(), hits.bookmarks.map { it.bookmarkId }.toSet())
            for (hit in hits.quotes) {
                val spans = hit.quoteSpans
                assertFalse("Matching source must highlight '$query'", spans.isEmpty())
                assertEquals(SearchMatchHighlighter.findMatchSpans(hit.quoteText, query), spans)
                for (span in spans) {
                    assertTrue(span.start in 0 until span.endExclusive)
                    assertTrue(span.endExclusive <= hit.quoteText.length)
                    assertFalse(span.start > 0 && Character.isLowSurrogate(hit.quoteText[span.start]))
                    assertFalse(span.endExclusive < hit.quoteText.length && Character.isLowSurrogate(hit.quoteText[span.endExclusive]))
                }
            }
        }
        assertEquals(listOf(MatchSpan(0, 3)), SearchMatchHighlighter.findMatchSpans("І\u0308жак", "їж"))
        assertEquals(listOf(MatchSpan(0, 3)), SearchMatchHighlighter.findMatchSpans("и\u0306орж", "йо"))
        assertEquals(listOf(MatchSpan(0, 2)), SearchMatchHighlighter.findMatchSpans("𐐀𐐁", "𐐨"))
        assertEquals(listOf(MatchSpan(2, 6)), SearchMatchHighlighter.findMatchSpans("الكتاب", "كتاب"))
        assertEquals(emptyList<MatchSpan>(), SearchMatchHighlighter.findMatchSpans("Sometimes", "time"))
        assertEquals(listOf(MatchSpan(0, 1)), SearchMatchHighlighter.findMatchSpans("a \u0301b", "a"))
    }

    @Test fun `AND may span fields and profile candidate batches preserve all hits`() = runTest {
        val books = listOf(
            Book(id = "both", title = "Time", author = "Wells", format = BookFormat.EPUB, addedAtMillis = 0L, quotes = (0..620).map {
                Quote("q$it", "Time for reading", 0, it.toLong())
            }),
            Book(id = "one", title = "Time", author = "Verne", format = BookFormat.EPUB, addedAtMillis = 0L),
        )
        StoreFixture.seed(root, books)
        assertEquals(listOf("both"), LibrarySearchRepository(root).searchBooks("time wells", books).map { it.book.id })
        val hits = ProfileSearchRepository(root).search("time", books, emptyList(), emptyList(), ReadingStats())
        assertEquals(621, hits.quotes.size)
        assertEquals(621, hits.quotes.map { it.quoteId }.toSet().size)
        assertTrue(hits.quotes.all { it.quoteSpans == listOf(MatchSpan(0, 4)) })
    }

    @Test fun `achievement finished count preserves repeat logs and excludes only finished links`() = runTest {
        val books = (1..4).map {
            Book(id = "b$it", title = "Book $it", format = BookFormat.EPUB, addedAtMillis = 0L,
                status = if (it == 4) ReadingStatus.READING else ReadingStatus.FINISHED)
        }
        val logs = listOf(
            ReadingLogEntry(id = "first", title = "First read", linkedBookId = "b1"),
            ReadingLogEntry(id = "repeat", title = "Repeat read", linkedBookId = "b1"),
            ReadingLogEntry(id = "paper", title = "Paper read"),
            ReadingLogEntry(id = "active", title = "Current read", linkedBookId = "b2", status = ReadingLogStatus.IN_PROGRESS),
        )
        StoreFixture.seed(root, books, readingLog = logs)
        val resolved = DatabaseManager.withSwapLock(root) { db ->
            AchievementsCatalog.resolveAndSync(db)
        }
        // Three completed log entries + b2 (its linked log is unfinished) + unlogged b3.
        assertEquals(5L, resolved.single { it.id == AchievementsCatalog.ID_FIRST_BOOK }.currentValue)
    }

    @Test fun `achievement persistence failure propagates without claiming a successful unlock`() = runTest {
        val books = listOf(Book(id = "finished", title = "Completed book", format = BookFormat.EPUB,
            addedAtMillis = 0L, status = ReadingStatus.FINISHED))
        StoreFixture.seed(root, books)
        DatabaseManager.withSwapLock(root) { db ->
            db.useWriterConnection { connection ->
                connection.executeSQL("""
                    CREATE TRIGGER block_achievement BEFORE INSERT ON achievement_progress
                    BEGIN SELECT RAISE(ABORT, 'injected achievement write failure'); END
                """.trimIndent())
            }
        }
        val failure = runCatching {
            ProfileSearchRepository(root).search("first", books, emptyList(), emptyList(), ReadingStats())
        }.exceptionOrNull()
        assertNotNull("A failed achievement commit must propagate", failure)
        DatabaseManager.withSwapLock(root) { db ->
            assertTrue(db.statsAndAchievementsDao().getAllAchievementProgress().isEmpty())
            db.useWriterConnection { it.executeSQL("DROP TRIGGER block_achievement") }
        }
        // Prove causality without depending on Room's exception wrapping: the same operation
        // must succeed and persist its unlock once the injected database fault is removed.
        val recovered = ProfileSearchRepository(root).search("first", books, emptyList(), emptyList(), ReadingStats())
        assertTrue(recovered.achievements.single { it.achievement.id == AchievementsCatalog.ID_FIRST_BOOK }.achievement.unlocked)
        DatabaseManager.withSwapLock(root) { db ->
            assertTrue(db.statsAndAchievementsDao().getAllAchievementProgress().single { it.achievementId == AchievementsCatalog.ID_FIRST_BOOK }.unlocked)
        }
    }

    @Test fun `stale profile inputs after restore cannot unlock achievements in restored library`() = runTest {
        val oldBooks = (0 until 25).map {
            Book(id = "old$it", title = "Old $it", format = BookFormat.EPUB, addedAtMillis = 0L,
                status = ReadingStatus.FINISHED, readingSeconds = 3_600L)
        }
        StoreFixture.seed(root, oldBooks)
        val replacementRoot = File(root, "replacement")
        try {
            StoreFixture.seed(replacementRoot, emptyList())
            val snapshot = File(root, "replacement.db")
            DatabaseManager.vacuumInto(replacementRoot, snapshot)
            DatabaseManager.replaceDatabaseFromStaged(root, snapshot)
            // A queued ViewModel query still holds the old book/stats StateFlow values.
            val results = ProfileSearchRepository(root).search("first", oldBooks, emptyList(), emptyList(),
                ReadingStats(dailySeconds = mapOf("2026-10-29" to 36_000L)))
            val first = results.achievements.single { it.achievement.id == AchievementsCatalog.ID_FIRST_BOOK }.achievement
            assertFalse(first.unlocked)
            assertEquals(0L, first.currentValue)
            DatabaseManager.withSwapLock(root) { db -> assertTrue(db.statsAndAchievementsDao().getAllAchievementProgress().isEmpty()) }
        } finally {
            DatabaseManager.closeForFilesRoot(replacementRoot)
        }
    }
}
