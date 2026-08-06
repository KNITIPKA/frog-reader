package com.example.frogreader.ui.nav

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
data object TrackerRoute

@Serializable
data class ReaderRoute(val bookId: String)

@Serializable
data object SettingsRoute

@Serializable
data object StatsRoute

/** The two destinations the navigation bar switches between. */
enum class NavTab {
    LIBRARY,
    PROFILE,
}
