package com.example.frogreader.data.search

import android.content.Context
import com.example.frogreader.R
import com.example.frogreader.data.BookRepository
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.StatsRepository
import com.example.frogreader.data.StoreFixture
import com.example.frogreader.data.backup.BackupArchive
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.Bookmark
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.Quote
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogSource
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingQueueEntry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SearchRepositoryTest {

    private val workDir = File("build/tmp/search_repo_test")
    private val filesRoot = File(workDir, "live")
    private val restoredRoot = File(workDir, "restored")

    @Before
    fun setUp() {
        DatabaseManager.closeAndDeleteForFilesRoot(filesRoot)
        DatabaseManager.closeAndDeleteForFilesRoot(restoredRoot)
        workDir.deleteRecursively()
        filesRoot.mkdirs()
        restoredRoot.mkdirs()
    }

    @After
    fun tearDown() {
        DatabaseManager.closeAndDeleteForFilesRoot(filesRoot)
        DatabaseManager.closeAndDeleteForFilesRoot(restoredRoot)
        workDir.deleteRecursively()
    }

    private fun contextFor(root: File): Context = mock(Context::class.java).also {
        `when`(it.filesDir).thenReturn(root)
        `when`(it.cacheDir).thenReturn(root)
    }

    @Test
    fun `library search ranks title above author above series above description and supports EN UK AR HE`() = runTest {
        val books = listOf(
            Book(id = "desc", title = "Quiet Forest", description = "Mentions Roland in the blurb", format = BookFormat.EPUB, addedAtMillis = 400L),
            Book(id = "series", title = "Book Four", series = "The Roland Cycle", format = BookFormat.EPUB, addedAtMillis = 300L),
            Book(id = "author", title = "Selected Essays", author = "Roland Barthes", format = BookFormat.EPUB, addedAtMillis = 200L),
            Book(id = "title", title = "The Song of Roland", author = "Anonymous", format = BookFormat.EPUB, addedAtMillis = 100L),
            Book(id = "uk", title = "Пам’ять і час", author = "Іван Франко", description = "Солов'їний спів", format = BookFormat.FB2, addedAtMillis = 50L),
            Book(id = "ar", title = "مُخْتَصَرُ تَارِيخِ الزَّمَنِ", author = "ستيفن هوكينغ", format = BookFormat.EPUB, addedAtMillis = 40L),
            Book(id = "he", title = "קִצּוּר תּוֹלְדוֹת הַזְּמַן", author = "סטיבן הוקינג", series = "סדרת תנ״ך המדע", format = BookFormat.EPUB, addedAtMillis = 30L),
        )
        StoreFixture.seed(filesRoot, books = books)
        val repo = BookRepository(contextFor(filesRoot))
        val search = LibrarySearchRepository(filesRoot)

        // Field tier priority:
        val rolandHits = search.searchBooks("roland", repo.books.value)
        assertEquals(listOf("title", "author", "series", "description"), rolandHits.map { it.book.id }.map {
            if (it == "desc") "description" else it
        })

        // Ukrainian apostrophe normalization (`'` finds `’`, and `ї` stays distinct):
        assertEquals(listOf("uk"), search.searchBooks("пам'ять", repo.books.value).map { it.book.id })
        assertEquals(listOf("uk"), search.searchBooks("солов’їний", repo.books.value).map { it.book.id })

        // Arabic unvocalized query matches vocalized title:
        assertEquals(listOf("ar"), search.searchBooks("الزمن", repo.books.value).map { it.book.id })

        // Hebrew unvocalized query + ASCII double-quote abbreviation matches niqqud & gershayim:
        assertEquals(listOf("he"), search.searchBooks("הזמן", repo.books.value).map { it.book.id })
        assertEquals(listOf("he"), search.searchBooks("""תנ"ך""", repo.books.value).map { it.book.id })

        // Word-prefix only: a fragment from the middle of a word never matches (`олов` ⊄ `солов'їний`),
        // while the start of the word does:
        assertTrue(search.searchBooks("олов", repo.books.value).isEmpty())
        assertEquals(listOf("uk"), search.searchBooks("солов", repo.books.value).map { it.book.id })

        // Arabic / Hebrew clitic awareness (`زمن` finds `الزَّمَنِ`, `זמן` finds `הַזְּמַן`):
        assertEquals(listOf("ar"), search.searchBooks("زمن", repo.books.value).map { it.book.id })
        assertEquals(listOf("he"), search.searchBooks("זמן", repo.books.value).map { it.book.id })
    }

    @Test
    fun `single letter query matches only word starts and ranks whole words first`() = runTest {
        val books = listOf(
            Book(id = "mid", title = "Вмостилася", format = BookFormat.EPUB, addedAtMillis = 300L),
            Book(id = "prefix", title = "Сапієнс", format = BookFormat.EPUB, addedAtMillis = 200L),
            Book(id = "letter", title = "С і Т", format = BookFormat.EPUB, addedAtMillis = 100L),
            Book(id = "desc-mid", title = "Інше", description = "Марселі", format = BookFormat.EPUB, addedAtMillis = 50L),
        )
        StoreFixture.seed(filesRoot, books = books)
        val repo = BookRepository(contextFor(filesRoot))
        val hits = LibrarySearchRepository(filesRoot).searchBooks("с", repo.books.value)

        // `Вмостилася` and `Марселі` contain `с` only mid-word, so they are not results;
        // the whole-word `С` outranks the word prefix `Сапієнс` despite library order.
        assertEquals(listOf("letter", "prefix"), hits.map { it.book.id })
    }

    @Test
    fun `profile search never matches or highlights letters in the middle of words`() = runTest {
        val books = listOf(
            Book(
                id = "uk-book",
                title = "Я бачу, вас цікавить пітьма",
                format = BookFormat.EPUB,
                fileName = "uk.epub",
                addedAtMillis = 100L,
                quotes = listOf(
                    Quote(id = "q-start", text = "сексуальності", chapterIndex = 3, createdAtMillis = 1L, bookId = "uk-book"),
                    Quote(id = "q-mid", text = "Кілька років тому вона вмостилася коло нього в Марселі", chapterIndex = 3, createdAtMillis = 2L, bookId = "uk-book"),
                    Quote(id = "q-whole", text = "Він сів і с усмішкою мовчав", chapterIndex = 3, createdAtMillis = 3L, bookId = "uk-book"),
                ),
                bookmarks = listOf(
                    Bookmark(id = "b-1", flatIndex = 1, chapterIndex = 34, preview = "Андрій нечутно відсунув стілець", createdAtMillis = 1L, bookId = "uk-book"),
                ),
            ),
        )
        StoreFixture.seed(filesRoot, books = books)
        val repo = BookRepository(contextFor(filesRoot))
        val results = ProfileSearchRepository(filesRoot).search(
            query = "с",
            books = repo.books.value,
            readingQueue = emptyList(),
            readingLog = emptyList(),
            stats = ReadingStats(),
        )

        // `вмостилася` / `Марселі` are not hits; the exact one-letter word ranks first.
        assertEquals(listOf("q-whole", "q-start"), results.quotes.map { it.quoteId })
        val start = results.quotes.single { it.quoteId == "q-start" }
        assertEquals(listOf(MatchSpan(0, 1)), start.quoteSpans)

        // Only the `с` that begins `стілець` is highlighted — not the one inside `відсунув`.
        val bookmark = results.bookmarks.single()
        val wordStart = bookmark.snippetText.indexOf("стілець")
        assertEquals(listOf(MatchSpan(wordStart, wordStart + 1)), bookmark.snippetSpans)
    }

    @Test
    fun `profile search strictly isolates categories counts visible results and survives VACUUM INTO backup`() = runTest {
        val books = listOf(
            // Book whose TITLE contains "Time", while its quote & bookmark do NOT contain "time":
            // Strict isolation rule: must NOT pollute Quotes or Bookmarks results for "time"!
            Book(
                id = "trap-book",
                title = "Time Traps & Illusions",
                format = BookFormat.PDF,
                fileName = "trap.pdf",
                addedAtMillis = 100L,
                quotes = listOf(
                    Quote(
                        id = "q-unrelated",
                        text = "Silence speaks louder than noise.",
                        chapterIndex = 0,
                        createdAtMillis = 1L,
                        bookId = "trap-book",
                        documentLocation = DocumentLocation.Pdf(pageIndex = 9),
                    )
                ),
                bookmarks = listOf(
                    Bookmark(
                        id = "b-unrelated",
                        flatIndex = 1,
                        chapterIndex = 0,
                        preview = "Nothing about clocks here.",
                        createdAtMillis = 1L,
                        bookId = "trap-book",
                    )
                ),
            ),
            Book(
                id = "art-reading",
                title = "The Art of Reading",
                format = BookFormat.PDF,
                fileName = "art.pdf",
                addedAtMillis = 200L,
                quotes = listOf(
                    Quote(
                        id = "q-1",
                        text = "Reading gives us time to pause and think.",
                        chapterIndex = 0,
                        createdAtMillis = 10L,
                        bookId = "art-reading",
                        documentLocation = DocumentLocation.Pdf(pageIndex = 41),
                    ),
                    Quote(
                        id = "q-3",
                        text = "Every quiet afternoon is borrowed time well lived.",
                        chapterIndex = 1,
                        createdAtMillis = 30L,
                        bookId = "art-reading",
                        documentLocation = DocumentLocation.Pdf(pageIndex = 88),
                    ),
                ),
                bookmarks = listOf(
                    Bookmark(
                        id = "b-1",
                        flatIndex = 5,
                        chapterIndex = 2,
                        preview = "In the end what defines a life is how we choose to spend our time when nobody demands it.",
                        createdAtMillis = 15L,
                        bookId = "art-reading",
                        documentLocation = DocumentLocation.Pdf(pageIndex = 64),
                    )
                ),
            ),
            Book(
                id = "habits",
                title = "Small Daily Habits",
                format = BookFormat.PDF,
                fileName = "habits.pdf",
                addedAtMillis = 300L,
                quotes = listOf(
                    Quote(
                        id = "q-2",
                        text = "Begin with one small step.",
                        note = "Make time to read every morning.",
                        chapterIndex = 0,
                        createdAtMillis = 20L,
                        bookId = "habits",
                        documentLocation = DocumentLocation.Pdf(pageIndex = 17),
                    )
                ),
            ),
        )
        val queue = listOf(
            ReadingQueueEntry(id = "rq-1", title = "A Brief History of Time", author = "Stephen Hawking"),
            ReadingQueueEntry(id = "rq-2", title = "Dune", author = "Frank Herbert"),
        )
        val log = listOf(
            ReadingLogEntry(
                id = "rl-1",
                title = "The Time Machine",
                author = "H. G. Wells",
                source = ReadingLogSource.PAPER,
                status = ReadingLogStatus.FINISHED,
            ),
        )
        // 10 hours read -> unlocks `Time Well Spent`!
        val stats = ReadingStats(dailySeconds = mapOf("2026-10-20" to 36_000L))

        StoreFixture.seed(filesRoot, books = books, readingQueue = queue, readingLog = log)
        StatsRepository(filesRootOverride = filesRoot).replaceAll(stats)

        val repo = BookRepository(contextFor(filesRoot))
        val statsRepo = StatsRepository(filesRootOverride = filesRoot)
        val profileSearch = ProfileSearchRepository(filesRoot)

        val results = profileSearch.search(
            query = "time",
            books = repo.books.value,
            readingQueue = repo.readingQueue.value,
            readingLog = repo.readingLog.value,
            stats = statsRepo.stats.value,
        )

        // Matches the exact counts from the user's mockup:
        // 3 Quotes + 1 Bookmark + 1 Reading Queue + 1 Reading Log + 1 Achievement = 7 results!
        assertEquals(3, results.quotes.size)
        assertTrue("Trap book quote must NOT match by book title", results.quotes.none { it.quoteId == "q-unrelated" })
        assertEquals(1, results.bookmarks.size)
        assertEquals("b-1", results.bookmarks.single().bookmarkId)
        assertEquals(1, results.readingQueue.size)
        assertEquals("rq-1", results.readingQueue.single().entryId)
        assertEquals(1, results.readingLog.size)
        assertEquals("rl-1", results.readingLog.single().entryId)
        assertEquals(1, results.achievements.size)
        assertEquals(AchievementsCatalog.ID_TIME_WELL_SPENT, results.achievements.single().achievement.id)
        assertTrue(results.achievements.single().achievement.unlocked)
        assertEquals("10 hours read", results.achievements.single().achievement.progressLabel)

        assertEquals(7, results.visibleTotalCount(emptySet()))
        assertEquals(4, results.visibleTotalCount(setOf(ProfileSearchCategory.QUOTES)))

        // Verify `VACUUM INTO` backup + restore keeps `doc_id == rowid` FTS5 links 100% intact!
        val archive = ByteArrayOutputStream()
        BackupArchive.write(archive, filesRoot, BackupSettings(), BackupMode.DATA, "2.0.0", 1L)
        BackupArchive.restore(ByteArrayInputStream(archive.toByteArray()), restoredRoot)

        val restoredRepo = BookRepository(contextFor(restoredRoot))
        val restoredStats = StatsRepository(filesRootOverride = restoredRoot)
        val afterRestore = ProfileSearchRepository(restoredRoot).search(
            query = "time",
            books = restoredRepo.books.value,
            readingQueue = restoredRepo.readingQueue.value,
            readingLog = restoredRepo.readingLog.value,
            stats = restoredStats.stats.value,
        )
        assertEquals(7, afterRestore.totalCountAllCategories)
        assertEquals(results.quotes.map { it.quoteId }.toSet(), afterRestore.quotes.map { it.quoteId }.toSet())

        // Verify runtime locale switching on Achievements without rewriting SQLite:
        val ukrainianStrings = object : AchievementStringResolver by DefaultEnglishAchievementStrings {
            override fun getString(resId: Int, vararg args: Any): String = when (resId) {
                R.string.achievement_time_well_spent_title -> "Час проведений з користю"
                R.string.achievement_time_well_spent_desc -> "Прочитайте загалом 10 годин"
                else -> DefaultEnglishAchievementStrings.getString(resId, *args)
            }
        }
        val ukAchievementResults = ProfileSearchRepository(restoredRoot).search(
            query = "користю",
            books = restoredRepo.books.value,
            readingQueue = restoredRepo.readingQueue.value,
            readingLog = restoredRepo.readingLog.value,
            stats = restoredStats.stats.value,
            achievementStrings = ukrainianStrings,
        )
        assertEquals(1, ukAchievementResults.achievements.size)
        assertEquals("Час проведений з користю", ukAchievementResults.achievements.single().achievement.title)
        assertTrue(ukAchievementResults.achievements.single().achievement.unlocked)
    }
}
