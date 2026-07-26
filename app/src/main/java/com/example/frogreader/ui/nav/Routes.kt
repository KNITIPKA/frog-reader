package com.example.frogreader.ui.nav

import kotlinx.serialization.Serializable

@Serializable
data object LibraryRoute

@Serializable
data class ReaderRoute(val bookId: String)

@Serializable
data object SettingsRoute

@Serializable
data object StatsRoute
