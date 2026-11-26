package com.example.frogreader.data.search

import androidx.room3.RoomRawQuery
import androidx.room3.withReadTransaction
import com.example.frogreader.data.ReadingStats
import com.example.frogreader.data.db.BookmarkEntity
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.DbJson
import com.example.frogreader.data.db.FrogReaderDatabase
import com.example.frogreader.data.db.QuoteEntity
import com.example.frogreader.data.db.ReadingLogEntity
import com.example.frogreader.data.db.ReadingQueueEntity
import com.example.frogreader.data.db.toDomain
import com.example.frogreader.data.model.Book
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.DocumentLocation
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.QuoteColor
import com.example.frogreader.data.model.ReadingLogEntry
import com.example.frogreader.data.model.ReadingLogStatus
import com.example.frogreader.data.model.ReadingQueueEntry
import com.example.frogreader.data.model.ResolvedAchievement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class QuoteSearchHit(
    val quoteId: String,
    val bookId: String,
    val bookTitle: String,
    val bookFormat: BookFormat,
    val hasBookFile: Boolean,
    val chapterIndex: Int,
    val documentLocation: DocumentLocation?,
    val quoteColor: QuoteColor,
    val quoteText: String,
    val quoteSpans: List<MatchSpan>,
    val noteText: String?,
    val noteSpans: List<MatchSpan>,
)

data class BookmarkSearchHit(
    val bookmarkId: String,
    val bookId: String,
    val bookTitle: String,
    val bookFormat: BookFormat,
    val hasBookFile: Boolean,
    val chapterIndex: Int,
    val documentLocation: DocumentLocation?,
    val orphaned: Boolean,
    val snippetText: String,
    val snippetSpans: List<MatchSpan>,
)

data class ReadingQueueSearchHit(
    val entryId: String,
    val title: String,
    val titleSpans: List<MatchSpan>,
    val author: String,
    val authorSpans: List<MatchSpan>,
    val noteSnippet: String?,
    val noteSpans: List<MatchSpan>,
)

data class ReadingLogSearchHit(
    val entryId: String,
    val title: String,
    val titleSpans: List<MatchSpan>,
    val author: String,
    val authorSpans: List<MatchSpan>,
    val series: String,
    val seriesSpans: List<MatchSpan>,
    val status: ReadingLogStatus,
    val reviewSnippet: String?,
    val reviewSpans: List<MatchSpan>,
)

data class AchievementSearchHit(
    val achievement: ResolvedAchievement,
    val titleSpans: List<MatchSpan>,
    val descriptionSpans: List<MatchSpan>,
)

data class ProfileSearchResults(
    val query: String,
    val quotes: List<QuoteSearchHit> = emptyList(),
    val bookmarks: List<BookmarkSearchHit> = emptyList(),
    val readingQueue: List<ReadingQueueSearchHit> = emptyList(),
    val readingLog: List<ReadingLogSearchHit> = emptyList(),
    val achievements: List<AchievementSearchHit> = emptyList(),
) {
    fun countFor(category: ProfileSearchCategory): Int = when (category) {
        ProfileSearchCategory.QUOTES -> quotes.size
        ProfileSearchCategory.BOOKMARKS -> bookmarks.size
        ProfileSearchCategory.READING_QUEUE -> readingQueue.size
        ProfileSearchCategory.READING_LOG -> readingLog.size
        ProfileSearchCategory.ACHIEVEMENTS -> achievements.size
    }

    fun visibleTotalCount(hiddenCategories: Set<ProfileSearchCategory>): Int =
        ProfileSearchCategory.entries
            .filterNot { it in hiddenCategories }
            .sumOf { countFor(it) }

    val totalCountAllCategories: Int
        get() = quotes.size + bookmarks.size + readingQueue.size + readingLog.size + achievements.size
}

/**
 * Global Profile Search engine backed by SQLite FTS5 (`quotes_fts`, `bookmarks_fts`,
 * `reading_queue_fts`, `reading_log_fts`) and localized in-memory [AchievementsCatalog] evaluation.
 *
 * Every query token must begin a word (`"token"*`), with Arabic/Hebrew clitic awareness — see
 * [SearchTextNormalizer.findWordPrefixMatches]. Hits are ordered by relevance (whole word >
 * word prefix > clitic), then by `bm25()`.
 *
 * Strictly enforces category field isolation:
 * - Quotes match ONLY `text` and `note` (never book title).
 * - Bookmarks match ONLY `preview` (never book title).
 * - Reading Queue matches ONLY `title`, `author`, and `note`.
 * - Reading Log matches ONLY `title`, `author`, `series`, and `review`.
 * - Achievements match ONLY localized `title` and `description`.
 */
class ProfileSearchRepository(
    private val filesRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun search(
        query: String,
        books: List<Book>,
        readingQueue: List<ReadingQueueEntry>,
        readingLog: List<ReadingLogEntry>,
        stats: ReadingStats,
        dailyGoalMinutes: Int = 30,
        achievementStrings: AchievementStringResolver = DefaultEnglishAchievementStrings,
    ): ProfileSearchResults = withContext(ioDispatcher) {
        val tokens = SearchTextNormalizer.tokenize(query)
        if (tokens.isEmpty()) return@withContext ProfileSearchResults(query = query)

        val ftsExpr = SearchTextNormalizer.toFts5PrefixQuery(query)
        val booksById = books.associateBy { it.id }

        val (resolvedAchievements, rawHits) = DatabaseManager.withSwapLock(filesRoot) { db ->
            val resolvedAchievements = AchievementsCatalog.resolveAndSync(
                db = db,
                dailyGoalMinutes = dailyGoalMinutes,
                strings = achievementStrings,
            )

            val rawHits = db.withReadTransaction {
                val searchDao = db.searchDao()

                val quoteIds = collectCandidateIds(
                    ftsExpr = ftsExpr,
                    tokens = tokens,
                    ftsRank = { expr ->
                        searchDao.rawRankQuotes(
                            RoomRawQuery(RANK_QUOTES_SQL) { it.bindText(1, expr) }
                        ).map { it.entityId }
                    },
                    substringFind = { tok -> searchDao.findQuotesByNormalizedSubstring(tok) },
                )

                val bookmarkIds = collectCandidateIds(
                    ftsExpr = ftsExpr,
                    tokens = tokens,
                    ftsRank = { expr ->
                        searchDao.rawRankBookmarks(
                            RoomRawQuery(RANK_BOOKMARKS_SQL) { it.bindText(1, expr) }
                        ).map { it.entityId }
                    },
                    substringFind = { tok -> searchDao.findBookmarksByNormalizedSubstring(tok) },
                )

                val queueIds = collectCandidateIds(
                    ftsExpr = ftsExpr,
                    tokens = tokens,
                    ftsRank = { expr ->
                        searchDao.rawRankReadingQueue(
                            RoomRawQuery(RANK_QUEUE_SQL) { it.bindText(1, expr) }
                        ).map { it.entityId }
                    },
                    substringFind = { tok -> searchDao.findReadingQueueByNormalizedSubstring(tok) },
                )

                val logIds = collectCandidateIds(
                    ftsExpr = ftsExpr,
                    tokens = tokens,
                    ftsRank = { expr ->
                        searchDao.rawRankReadingLog(
                            RoomRawQuery(RANK_LOG_SQL) { it.bindText(1, expr) }
                        ).map { it.entityId }
                    },
                    substringFind = { tok -> searchDao.findReadingLogByNormalizedSubstring(tok) },
                )

                val allQuotesById = quoteIds.chunked(500).flatMap { searchDao.getQuoteCandidates(it) }.associateBy { it.id }
                val allBookmarksById = bookmarkIds.chunked(500).flatMap { searchDao.getBookmarkCandidates(it) }.associateBy { it.id }
                val allQueueById = queueIds.chunked(500).flatMap { searchDao.getQueueCandidates(it) }.associateBy { it.id }
                val allLogById = logIds.chunked(500).flatMap { searchDao.getLogCandidates(it) }.associateBy { it.id }

                RawDbMatches(
                    quotes = quoteIds.mapNotNull { allQuotesById[it] },
                    bookmarks = bookmarkIds.mapNotNull { allBookmarksById[it] },
                    queue = queueIds.mapNotNull { allQueueById[it] },
                    logs = logIds.mapNotNull { allLogById[it] },
                )
            }

            resolvedAchievements to rawHits
        }

        val quoteHits = rawHits.quotes.mapNotNull { entity ->
            currentCoroutineContext().ensureActive()
            buildQuoteHit(entity, booksById[entity.bookId], query, tokens)
        }.byRelevance()
        val bookmarkHits = rawHits.bookmarks.mapNotNull { entity ->
            currentCoroutineContext().ensureActive()
            buildBookmarkHit(entity, booksById[entity.bookId], query, tokens)
        }.byRelevance()
        val queueHits = rawHits.queue.mapNotNull { entity ->
            currentCoroutineContext().ensureActive()
            buildQueueHit(entity, query, tokens)
        }.byRelevance()
        val logHits = rawHits.logs.mapNotNull { entity ->
            currentCoroutineContext().ensureActive()
            buildLogHit(entity, query, tokens)
        }.byRelevance()
        val achievementHits = searchAchievements(resolvedAchievements, query, tokens)

        ProfileSearchResults(
            query = query,
            quotes = quoteHits,
            bookmarks = bookmarkHits,
            readingQueue = queueHits,
            readingLog = logHits,
            achievements = achievementHits,
        )
    }

    private data class RawDbMatches(
        val quotes: List<QuoteEntity>,
        val bookmarks: List<BookmarkEntity>,
        val queue: List<ReadingQueueEntity>,
        val logs: List<ReadingLogEntity>,
    )

    companion object {
        private const val RANK_QUOTES_SQL = """
            SELECT entity_id,
                   bm25(quotes_fts, 0.0, 5.0, 3.0) AS score
            FROM quotes_fts
            WHERE quotes_fts MATCH ?
            ORDER BY score ASC
        """

        private const val RANK_BOOKMARKS_SQL = """
            SELECT entity_id,
                   bm25(bookmarks_fts, 0.0, 5.0) AS score
            FROM bookmarks_fts
            WHERE bookmarks_fts MATCH ?
            ORDER BY score ASC
        """

        private const val RANK_QUEUE_SQL = """
            SELECT entity_id,
                   bm25(reading_queue_fts, 0.0, 10.0, 6.0, 3.0) AS score
            FROM reading_queue_fts
            WHERE reading_queue_fts MATCH ?
            ORDER BY score ASC
        """

        private const val RANK_LOG_SQL = """
            SELECT entity_id,
                   bm25(reading_log_fts, 0.0, 10.0, 6.0, 4.0, 2.0) AS score
            FROM reading_log_fts
            WHERE reading_log_fts MATCH ?
            ORDER BY score ASC
        """

        private suspend fun collectCandidateIds(
            ftsExpr: String?,
            tokens: List<String>,
            ftsRank: suspend (String) -> List<String>,
            substringFind: suspend (String) -> List<String>,
        ): List<String> = collectWordPrefixCandidates(ftsExpr, tokens, ftsRank, substringFind)

        /**
         * Orders verified hits by relevance: whole-word matches beat word-prefix matches, which
         * beat Arabic/Hebrew clitic matches; ties keep the FTS5 `bm25()` order.
         */
        private fun <T> List<Pair<Int, T>>.byRelevance(): List<T> =
            withIndex()
                .sortedWith(compareByDescending<IndexedValue<Pair<Int, T>>> { it.value.first }.thenBy { it.index })
                .map { it.value.second }

        private fun buildQuoteHit(
            entity: QuoteEntity,
            book: Book?,
            query: String,
            tokens: List<String>,
        ): Pair<Int, QuoteSearchHit>? {
            val normText = SearchTextNormalizer.normalize(entity.text)
            val normNote = SearchTextNormalizer.normalize(entity.note)
            val score = SearchTextNormalizer.matchScore(listOf(normText, normNote), tokens)
            if (score == 0) return null

            val domain = entity.toDomain()
            val cleanNote = domain.note?.takeIf { it.isNotBlank() }
            return score to QuoteSearchHit(
                quoteId = domain.id,
                bookId = entity.bookId,
                bookTitle = book?.title.orEmpty(),
                bookFormat = book?.format ?: BookFormat.EPUB,
                hasBookFile = book?.fileName != null,
                chapterIndex = domain.chapterIndex,
                documentLocation = domain.documentLocation,
                quoteColor = domain.color,
                quoteText = domain.text,
                quoteSpans = SearchMatchHighlighter.findMatchSpans(domain.text, query),
                noteText = cleanNote,
                noteSpans = cleanNote?.let { SearchMatchHighlighter.findMatchSpans(it, query) }.orEmpty(),
            )
        }

        private fun buildBookmarkHit(
            entity: BookmarkEntity,
            book: Book?,
            query: String,
            tokens: List<String>,
        ): Pair<Int, BookmarkSearchHit>? {
            val normPreview = SearchTextNormalizer.normalize(entity.preview)
            val score = SearchTextNormalizer.matchScore(listOf(normPreview), tokens)
            if (score == 0) return null

            val domain = entity.toDomain()
            val snippet = SearchMatchHighlighter.extractContextSnippet(domain.preview, query)
            return score to BookmarkSearchHit(
                bookmarkId = domain.id,
                bookId = entity.bookId,
                bookTitle = book?.title.orEmpty(),
                bookFormat = book?.format ?: BookFormat.EPUB,
                hasBookFile = book?.fileName != null && !domain.orphaned,
                chapterIndex = domain.chapterIndex,
                documentLocation = domain.documentLocation,
                orphaned = domain.orphaned,
                snippetText = snippet.text,
                snippetSpans = snippet.spans,
            )
        }

        private fun buildQueueHit(
            entity: ReadingQueueEntity,
            query: String,
            tokens: List<String>,
        ): Pair<Int, ReadingQueueSearchHit>? {
            val normTitle = SearchTextNormalizer.normalize(entity.title)
            val normAuthor = SearchTextNormalizer.normalize(entity.author)
            val normNote = SearchTextNormalizer.normalize(entity.note)
            val score = SearchTextNormalizer.matchScore(listOf(normTitle, normAuthor, normNote), tokens)
            if (score == 0) return null
            val noteMatches = entity.note.isNotBlank() &&
                tokens.any { SearchTextNormalizer.containsWordPrefix(normNote, it) }
            val noteSnippet = if (noteMatches) {
                SearchMatchHighlighter.extractContextSnippet(entity.note, query)
            } else {
                null
            }
            return score to ReadingQueueSearchHit(
                entryId = entity.id,
                title = entity.title,
                titleSpans = SearchMatchHighlighter.findMatchSpans(entity.title, query),
                author = entity.author,
                authorSpans = SearchMatchHighlighter.findMatchSpans(entity.author, query),
                noteSnippet = noteSnippet?.text,
                noteSpans = noteSnippet?.spans.orEmpty(),
            )
        }

        private fun buildLogHit(
            entity: ReadingLogEntity,
            query: String,
            tokens: List<String>,
        ): Pair<Int, ReadingLogSearchHit>? {
            val normTitle = SearchTextNormalizer.normalize(entity.title)
            val normAuthor = SearchTextNormalizer.normalize(entity.author)
            val normSeries = SearchTextNormalizer.normalize(entity.series)
            val normReview = SearchTextNormalizer.normalize(entity.review)
            val score = SearchTextNormalizer.matchScore(
                listOf(normTitle, normAuthor, normSeries, normReview),
                tokens,
            )
            if (score == 0) return null
            val domain = entity.toDomain()
            val reviewMatches = domain.review.isNotBlank() &&
                tokens.any { SearchTextNormalizer.containsWordPrefix(normReview, it) }
            val reviewSnippet = if (reviewMatches) {
                SearchMatchHighlighter.extractContextSnippet(domain.review, query)
            } else {
                null
            }
            return score to ReadingLogSearchHit(
                entryId = domain.id,
                title = domain.title,
                titleSpans = SearchMatchHighlighter.findMatchSpans(domain.title, query),
                author = domain.author,
                authorSpans = SearchMatchHighlighter.findMatchSpans(domain.author, query),
                series = domain.series,
                seriesSpans = SearchMatchHighlighter.findMatchSpans(domain.series, query),
                status = domain.status,
                reviewSnippet = reviewSnippet?.text,
                reviewSpans = reviewSnippet?.spans.orEmpty(),
            )
        }

        private fun searchAchievements(
            achievements: List<ResolvedAchievement>,
            query: String,
            tokens: List<String>,
        ): List<AchievementSearchHit> {
            return achievements.mapNotNull { item ->
                val normTitle = SearchTextNormalizer.normalize(item.title)
                val normDesc = SearchTextNormalizer.normalize(item.description)
                val score = SearchTextNormalizer.matchScore(listOf(normTitle, normDesc), tokens)
                if (score == 0) return@mapNotNull null

                val titleRank = if (SearchTextNormalizer.matchesAllTokens(listOf(normTitle), tokens)) 0 else 1
                val hit = AchievementSearchHit(
                    achievement = item,
                    titleSpans = SearchMatchHighlighter.findMatchSpans(item.title, query),
                    descriptionSpans = SearchMatchHighlighter.findMatchSpans(item.description, query),
                )
                Triple(titleRank, score, hit)
            }
                .sortedWith(
                    compareBy<Triple<Int, Int, AchievementSearchHit>> { it.first }
                        .thenByDescending { it.second }
                        .thenByDescending { it.third.achievement.unlocked }
                        .thenBy { it.third.achievement.displayOrder }
                )
                .map { it.third }
        }
    }
}
