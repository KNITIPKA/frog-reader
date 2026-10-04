package com.example.frogreader.ui.nav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

/**
 * Kept alongside [TrackerRoute] and [StatsRoute], which are no longer reachable
 * from the UI: the second tab now leads to [ProfileRoute], and the tracker and
 * stats screens will be folded into Profile when it is built.
 */
@Serializable
data object ProfileRoute

@Serializable
// Stable serialized names preserve navigation state from before the UI rename.
@SerialName("com.example.frogreader.ui.nav.QuotesBookmarksRoute")
data object QuotesAndBookmarksRoute

@Serializable
@SerialName("com.example.frogreader.ui.nav.ReadingListRoute")
data object ReadingQueueRoute

@Serializable
@SerialName("com.example.frogreader.ui.nav.ReadingHistoryRoute")
data object ReadingLogRoute

@Serializable
data class ReadingLogInfoRoute(val entryId: String)

@Serializable
data object ReadingLogCameraRoute

@Serializable
data object TrackerRoute

@Serializable
data class ReaderRoute(val bookId: String, val quoteId: String? = null, val bookmarkId: String? = null)

@Serializable
data object SettingsRoute

@Serializable
data object StatsRoute

/** The two destinations the navigation bar switches between. */
enum class NavTab {
    LIBRARY,
    PROFILE,
}

/** Edits the actual stored book file. */
@Serializable
data class BookMetadataRoute(val bookId: String)

@Serializable
data class BookInfoRoute(val bookId: String, val reviewRequested: Boolean = false)
