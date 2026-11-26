package com.example.frogreader.data.search

import androidx.room3.RoomRawQuery
import androidx.room3.withReadTransaction
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.db.FrogReaderDatabase
import com.example.frogreader.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Ranked library book hit produced by [LibrarySearchRepository].
 *
 * [tier] preserves FrogReader's intuitive priority hierarchy:
 * - `0` = Title match
 * - `1` = Author match
 * - `2` = Series match
 * - `3` = Description match
 *
 * Within each tier, higher [relevance] wins (every whole-word hit outranks a word-prefix hit, see
 * [SearchTextNormalizer.matchScore]), and the library's own order breaks remaining ties.
 */
data class RankedLibraryBook(
    val book: Book,
    val tier: Int,
    val relevance: Int,
    val bm25Score: Double = Double.MAX_VALUE,
)

/**
 * Native SQLite FTS5 word-prefix search for the Library screen.
 *
 * Every query token must begin a word of the title, author, series or description (`"token"*`),
 * so `с` finds `Сапієнс`, not every title that merely contains the letter. Arabic/Hebrew tokens
 * additionally match behind attached clitics (`كتاب` → `الكتاب`, `ספר` → `והספר`).
 *
 * Column weights in `books_fts`: `entity_id = 0.0`, `title = 10.0`, `author = 6.0`,
 * `series = 4.0`, `description = 1.0`.
 */
class LibrarySearchRepository(
    private val filesRoot: File,
) {

    suspend fun searchBooks(
        query: String,
        currentBooks: List<Book>,
    ): List<RankedLibraryBook> = withContext(Dispatchers.IO) {
        val tokens = SearchTextNormalizer.tokenize(query)
        if (tokens.isEmpty() || currentBooks.isEmpty()) return@withContext emptyList()

        val ftsExpr = SearchTextNormalizer.toFts5PrefixQuery(query)
        val booksById = currentBooks.associateBy { it.id }
        val originalOrderIndex = HashMap<String, Int>(currentBooks.size * 2)
        currentBooks.forEachIndexed { index, book -> originalOrderIndex[book.id] = index }

        val (ftsScores, candidateIds) = DatabaseManager.withSwapLock(filesRoot) { db ->
            queryCandidates(db, ftsExpr, tokens)
        }

        val ranked = candidateIds.mapNotNull { id ->
            currentCoroutineContext().ensureActive()
            val book = booksById[id] ?: return@mapNotNull null
            rankBook(book, tokens, bm25Score = ftsScores[id] ?: Double.MAX_VALUE)
        }

        ranked.sortedWith(rankingComparator { originalOrderIndex[it.id] ?: Int.MAX_VALUE })
    }

    companion object {
        const val TIER_TITLE = 0
        const val TIER_AUTHOR = 1
        const val TIER_SERIES = 2
        const val TIER_DESCRIPTION = 3

        private const val RANK_BOOKS_SQL = """
            SELECT entity_id,
                   bm25(books_fts, 0.0, 10.0, 6.0, 4.0, 1.0) AS score
            FROM books_fts
            WHERE books_fts MATCH ?
            ORDER BY score ASC
        """

        private suspend fun queryCandidates(
            db: FrogReaderDatabase,
            ftsExpr: String?,
            tokens: List<String>,
        ): Pair<Map<String, Double>, List<String>> = db.withReadTransaction {
            val searchDao = db.searchDao()
            val ftsScores = LinkedHashMap<String, Double>()
            val candidates = collectWordPrefixCandidates(
                ftsExpr = ftsExpr,
                tokens = tokens,
                ftsRank = { expr ->
                    searchDao.rawRankBooks(RoomRawQuery(RANK_BOOKS_SQL) { it.bindText(1, expr) })
                        .map { row ->
                            ftsScores[row.entityId] = row.score
                            row.entityId
                        }
                },
                substringFind = { token -> searchDao.findBooksByNormalizedSubstring(token) },
            )
            ftsScores to candidates
        }

        /** `Tier ↑`, then `relevance ↓`, then the caller's library order. */
        fun rankingComparator(libraryOrder: (Book) -> Int): Comparator<RankedLibraryBook> =
            compareBy<RankedLibraryBook> { it.tier }
                .thenByDescending { it.relevance }
                .thenBy { libraryOrder(it.book) }

        /**
         * Ranks [book] against normalized [queryTokens], or returns `null` when some token does not
         * begin a word in any searchable field. The tier is the most important field that answers
         * the whole query on its own (falling back to the most important field that answers any
         * token), and [RankedLibraryBook.relevance] scores the match within that tier.
         */
        fun rankBook(
            book: Book,
            queryTokens: List<String>,
            bm25Score: Double = Double.MAX_VALUE,
        ): RankedLibraryBook? {
            if (queryTokens.isEmpty()) return null
            val fields = listOf(
                SearchTextNormalizer.normalize(book.title),
                SearchTextNormalizer.normalize(book.author),
                SearchTextNormalizer.normalize(book.series),
                SearchTextNormalizer.normalize(book.description),
            )
            val overall = SearchTextNormalizer.matchScore(fields, queryTokens)
            if (overall == 0) return null

            for ((tier, field) in fields.withIndex()) {
                val score = SearchTextNormalizer.matchScore(listOf(field), queryTokens)
                if (score > 0) return RankedLibraryBook(book, tier, score, bm25Score)
            }
            val tier = fields.indexOfFirst { field ->
                queryTokens.any { SearchTextNormalizer.containsWordPrefix(field, it) }
            }.coerceAtLeast(TIER_TITLE)
            return RankedLibraryBook(book, tier, overall, bm25Score)
        }

        /** Field tier only (`Title = 0` … `Description = 3`), or `null` when [book] does not match. */
        fun bestFieldTier(book: Book, queryTokens: List<String>): Int? = rankBook(book, queryTokens)?.tier
    }
}
