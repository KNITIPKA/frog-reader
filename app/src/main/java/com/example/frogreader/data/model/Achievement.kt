package com.example.frogreader.data.model

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

/**
 * Persisted progress state for an achievement.
 *
 * Human-readable titles and descriptions live in Android string resources rather than SQLite so
 * switching the UI language (English today; Ukrainian, Arabic, and Hebrew later) never leaves
 * stale strings in the database.
 */
@Serializable
data class AchievementProgress(
    val achievementId: String,
    val currentValue: Long = 0L,
    val unlocked: Boolean = false,
    val unlockedAtMillis: Long? = null,
)

/** Static localized definition of a reader achievement paired with its runtime progress. */
data class AchievementDefinition(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    @param:StringRes val progressFormatRes: Int? = null,
    val targetValue: Long = 1L,
    val displayOrder: Int = 0,
)

/** Resolved achievement item ready for UI display and localized in-memory search. */
data class ResolvedAchievement(
    val id: String,
    val title: String,
    val description: String,
    val progressLabel: String?,
    val targetValue: Long,
    val currentValue: Long,
    val unlocked: Boolean,
    val unlockedAtMillis: Long?,
    val displayOrder: Int,
)
