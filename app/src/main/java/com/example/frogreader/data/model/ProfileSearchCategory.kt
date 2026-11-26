package com.example.frogreader.data.model

import kotlinx.serialization.Serializable

/**
 * Categories shown in Profile Global Search, supporting user-defined ordering and visibility.
 */
@Serializable
enum class ProfileSearchCategory {
    QUOTES,
    BOOKMARKS,
    READING_QUEUE,
    READING_LOG,
    ACHIEVEMENTS;

    companion object {
        val DEFAULT_ORDER: List<ProfileSearchCategory> = entries.toList()

        fun normalizeOrder(raw: List<ProfileSearchCategory>): List<ProfileSearchCategory> {
            val result = ArrayList<ProfileSearchCategory>(entries.size)
            for (cat in raw) {
                if (cat !in result) result += cat
            }
            for (cat in entries) {
                if (cat !in result) result += cat
            }
            return result
        }
    }
}
