package com.example.frogreader.data.search

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.room3.withWriteTransaction
import com.example.frogreader.R
import com.example.frogreader.data.db.AchievementProgressEntity
import com.example.frogreader.data.db.FrogReaderDatabase
import com.example.frogreader.data.db.buildReadingStats
import com.example.frogreader.data.model.AchievementDefinition
import com.example.frogreader.data.model.ResolvedAchievement
import com.example.frogreader.data.streakDays
import java.time.LocalDate

/**
 * Abstraction over Android string/plural lookup so [AchievementsCatalog] resolves localized strings
 * at runtime (EN today; UK, AR, HE in future) while remaining 100% testable on pure host JVMs.
 */
interface AchievementStringResolver {
    fun getString(@StringRes resId: Int, vararg args: Any): String
    fun getQuantityString(@PluralsRes resId: Int, quantity: Int, vararg args: Any): String
}

class ContextAchievementStringResolver(private val context: Context) : AchievementStringResolver {
    override fun getString(resId: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args)

    override fun getQuantityString(resId: Int, quantity: Int, vararg args: Any): String =
        if (args.isEmpty()) context.resources.getQuantityString(resId, quantity)
        else context.resources.getQuantityString(resId, quantity, *args)
}

/** Pure-JVM English fallback matching `res/values/strings.xml` and `res/values/counts.xml`. */
object DefaultEnglishAchievementStrings : AchievementStringResolver {
    override fun getString(resId: Int, vararg args: Any): String = when (resId) {
        R.string.achievement_first_book_title -> "First Chapter Closed"
        R.string.achievement_first_book_desc -> "Finish your first book"
        R.string.achievement_five_books_title -> "Steady Reader"
        R.string.achievement_five_books_desc -> "Finish 5 books"
        R.string.achievement_twenty_five_books_title -> "Personal Library"
        R.string.achievement_twenty_five_books_desc -> "Finish 25 books"
        R.string.achievement_time_well_spent_title -> "Time Well Spent"
        R.string.achievement_time_well_spent_desc -> "Read for 10 hours in total"
        R.string.achievement_hundred_hours_title -> "Deep Immersion"
        R.string.achievement_hundred_hours_desc -> "Read for 100 hours in total"
        R.string.achievement_week_streak_title -> "Week of Reading"
        R.string.achievement_week_streak_desc -> "Keep a 7-day reading streak"
        R.string.achievement_quote_keeper_title -> "Keeper of Words"
        R.string.achievement_quote_keeper_desc -> "Save 10 quotes in your books"
        R.string.achievement_curious_queue_title -> "Next on the Desk"
        R.string.achievement_curious_queue_desc -> "Keep 5 books in your Reading Queue"
        else -> ""
    }

    override fun getQuantityString(resId: Int, quantity: Int, vararg args: Any): String {
        val n = (args.firstOrNull() as? Number)?.toInt() ?: quantity
        return when (resId) {
            R.plurals.achievement_books_read -> if (quantity == 1) "$n book finished" else "$n books finished"
            R.plurals.achievement_hours_read -> if (quantity == 1) "$n hour read" else "$n hours read"
            R.plurals.achievement_streak_days -> "$n day streak"
            R.plurals.achievement_quotes_saved -> if (quantity == 1) "$n quote saved" else "$n quotes saved"
            R.plurals.achievement_queue_books -> if (quantity == 1) "$n book queued" else "$n books queued"
            else -> "$n"
        }
    }
}

/**
 * Localized catalog of reader achievements paired with persisted SQLite `achievement_progress`.
 */
object AchievementsCatalog {

    const val ID_FIRST_BOOK = "first_book"
    const val ID_FIVE_BOOKS = "five_books"
    const val ID_TWENTY_FIVE_BOOKS = "twenty_five_books"
    const val ID_TIME_WELL_SPENT = "time_well_spent"
    const val ID_HUNDRED_HOURS = "hundred_hours"
    const val ID_WEEK_STREAK = "week_streak"
    const val ID_QUOTE_KEEPER = "quote_keeper"
    const val ID_CURIOUS_QUEUE = "curious_queue"

    private data class Spec(
        val definition: AchievementDefinition,
        @param:PluralsRes val pluralProgressRes: Int,
        val evaluate: (Metrics) -> Long,
    )

    private data class Metrics(
        val finishedBooks: Long,
        val totalHoursRead: Long,
        val streakDays: Long,
        val savedQuotes: Long,
        val queuedBooks: Long,
    )

    private val specs: List<Spec> = listOf(
        Spec(
            definition = AchievementDefinition(
                id = ID_FIRST_BOOK,
                titleRes = R.string.achievement_first_book_title,
                descriptionRes = R.string.achievement_first_book_desc,
                targetValue = 1L,
                displayOrder = 0,
            ),
            pluralProgressRes = R.plurals.achievement_books_read,
            evaluate = { it.finishedBooks },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_FIVE_BOOKS,
                titleRes = R.string.achievement_five_books_title,
                descriptionRes = R.string.achievement_five_books_desc,
                targetValue = 5L,
                displayOrder = 1,
            ),
            pluralProgressRes = R.plurals.achievement_books_read,
            evaluate = { it.finishedBooks },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_TWENTY_FIVE_BOOKS,
                titleRes = R.string.achievement_twenty_five_books_title,
                descriptionRes = R.string.achievement_twenty_five_books_desc,
                targetValue = 25L,
                displayOrder = 2,
            ),
            pluralProgressRes = R.plurals.achievement_books_read,
            evaluate = { it.finishedBooks },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_TIME_WELL_SPENT,
                titleRes = R.string.achievement_time_well_spent_title,
                descriptionRes = R.string.achievement_time_well_spent_desc,
                targetValue = 10L,
                displayOrder = 3,
            ),
            pluralProgressRes = R.plurals.achievement_hours_read,
            evaluate = { it.totalHoursRead },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_HUNDRED_HOURS,
                titleRes = R.string.achievement_hundred_hours_title,
                descriptionRes = R.string.achievement_hundred_hours_desc,
                targetValue = 100L,
                displayOrder = 4,
            ),
            pluralProgressRes = R.plurals.achievement_hours_read,
            evaluate = { it.totalHoursRead },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_WEEK_STREAK,
                titleRes = R.string.achievement_week_streak_title,
                descriptionRes = R.string.achievement_week_streak_desc,
                targetValue = 7L,
                displayOrder = 5,
            ),
            pluralProgressRes = R.plurals.achievement_streak_days,
            evaluate = { it.streakDays },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_QUOTE_KEEPER,
                titleRes = R.string.achievement_quote_keeper_title,
                descriptionRes = R.string.achievement_quote_keeper_desc,
                targetValue = 10L,
                displayOrder = 6,
            ),
            pluralProgressRes = R.plurals.achievement_quotes_saved,
            evaluate = { it.savedQuotes },
        ),
        Spec(
            definition = AchievementDefinition(
                id = ID_CURIOUS_QUEUE,
                titleRes = R.string.achievement_curious_queue_title,
                descriptionRes = R.string.achievement_curious_queue_desc,
                targetValue = 5L,
                displayOrder = 7,
            ),
            pluralProgressRes = R.plurals.achievement_queue_books,
            evaluate = { it.queuedBooks },
        ),
    )

    val definitions: List<AchievementDefinition> get() = specs.map { it.definition }

    /**
     * Evaluates persisted reading metrics, merges with persisted [AchievementProgressEntity] rows in
     * [db] (preserving historic unlocks), writes any newly unlocked/advanced rows, and resolves
     * localized titles/descriptions via [strings].
     */
    suspend fun resolveAndSync(
        db: FrogReaderDatabase,
        dailyGoalMinutes: Int = 30,
        strings: AchievementStringResolver = DefaultEnglishAchievementStrings,
        today: LocalDate = LocalDate.now(),
        nowMillis: Long = System.currentTimeMillis(),
    ): List<ResolvedAchievement> = db.withWriteTransaction {
        // Read metrics from the active database in the same transaction as progress updates.
        // Repository flows may still carry the previous library briefly after a restore.
        val dao = db.statsAndAchievementsDao()
        val persisted = db.searchDao().getAchievementMetrics()
        val stats = buildReadingStats(dao.getAllDailyStats(), dao.getStatsMeta())
        val metrics = Metrics(
            finishedBooks = persisted.finishedBooks,
            totalHoursRead = persisted.readingSeconds / 3_600L,
            streakDays = stats.streakDays(dailyGoalMinutes, today).toLong(),
            savedQuotes = persisted.savedQuotes,
            queuedBooks = persisted.queuedBooks,
        )

        val storedById = dao.getAllAchievementProgress().associateBy { it.achievementId }
        val updates = ArrayList<AchievementProgressEntity>()

        val resolved = specs.map { spec ->
            val def = spec.definition
            val liveValue = spec.evaluate(metrics)
            val prev = storedById[def.id]
            val bestValue = maxOf(liveValue, prev?.currentValue ?: 0L)
            val unlocked = (prev?.unlocked == true) || bestValue >= def.targetValue
            val unlockedAt = when {
                prev?.unlockedAtMillis != null -> prev.unlockedAtMillis
                unlocked -> nowMillis
                else -> null
            }

            if (prev == null && (bestValue > 0L || unlocked) ||
                prev != null && (prev.currentValue != bestValue || prev.unlocked != unlocked)
            ) {
                updates += AchievementProgressEntity(
                    achievementId = def.id,
                    currentValue = bestValue,
                    unlocked = unlocked,
                    unlockedAtMillis = unlockedAt,
                )
            }

            val quantityForLabel = bestValue.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val progressLabel = strings.getQuantityString(
                spec.pluralProgressRes,
                quantityForLabel,
                quantityForLabel,
            )

            ResolvedAchievement(
                id = def.id,
                title = strings.getString(def.titleRes),
                description = strings.getString(def.descriptionRes),
                progressLabel = progressLabel,
                targetValue = def.targetValue,
                currentValue = bestValue,
                unlocked = unlocked,
                unlockedAtMillis = unlockedAt,
                displayOrder = def.displayOrder,
            )
        }

        for (entity in updates) {
            dao.upsertAchievementProgress(entity)
        }

        resolved
    }
}
