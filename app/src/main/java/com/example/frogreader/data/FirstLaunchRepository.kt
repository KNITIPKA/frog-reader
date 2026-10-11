package com.example.frogreader.data

import com.example.frogreader.data.model.FirstLaunchState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Evidence of use before the welcome screen was introduced, including untouched upgrades. */
internal data class FirstLaunchHistory(
    val hasSavedPreferences: Boolean = false,
    val hasSavedAppearance: Boolean = false,
    val hasReaderData: Boolean = false,
    val wasUpdatedInstall: Boolean = false,
) {
    val isExistingReader: Boolean
        get() = hasSavedPreferences || hasSavedAppearance || hasReaderData || wasUpdatedInstall
}

/** A stored empty library still proves prior use; backup copies count after an interrupted write. */
internal fun existingReaderFiles(filesDir: File): Boolean =
    listOf("library.json", "userdata.json", "progress.json", "reading_stats.json")
        .any { name -> listOf(name, "$name.bak", "$name.tmp").any { File(filesDir, it).exists() } } ||
        listOf("books", "fonts").any { File(filesDir, it).listFiles()?.isNotEmpty() == true }

/** Merge the welcome's choices into the latest preferences, retaining unrelated changes. */
internal fun AppSettings.withWelcomeChoices(draft: AppSettings): AppSettings = copy(
    readerChromeStyle = draft.readerChromeStyle,
    startupDestination = draft.startupDestination,
    theme = draft.theme,
    followSystemTheme = draft.followSystemTheme,
    lightThemeDefault = draft.lightThemeDefault,
    dynamicColor = draft.dynamicColor,
)

/**
 * A release-independent marker under noBackupFilesDir. Persisting pending before any preview
 * side effects keeps an interrupted first launch eligible even when preferences later exist.
 */
internal class FirstLaunchRepository(
    stateFile: File,
    private val showOnEveryLaunch: Boolean = false,
    private val hasExistingData: suspend () -> Boolean,
) {
    private val mutex = Mutex()
    private val store = AtomicJsonFile(
        file = stateFile,
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        serializer = FirstLaunchState.serializer(),
        looksValid = { it.contains("\"completed\"") },
    )

    /** The saveable Activity flag dismisses debug replay until the next fresh launch. */
    suspend fun shouldShowWelcome(completedForLaunch: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (completedForLaunch) return@withContext false
            mutex.withLock {
                val stored = store.read()
                if (stored != null) return@withLock showOnEveryLaunch || !stored.completed
                val initial = FirstLaunchState(completed = hasExistingData())
                store.write(initial)
                showOnEveryLaunch || !initial.completed
            }
        }

    /** Save choices before completion; a failed callback or marker write retains the welcome. */
    suspend fun finish(
        draft: AppSettings? = null,
        saveChoices: suspend (AppSettings) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Initialization may have failed before its pending marker was written. Retry that
            // first so a partial settings save can never make this look like a legacy install.
            if (runCatching { store.read() }.getOrNull() == null) {
                store.write(FirstLaunchState())
            }
            if (draft != null) saveChoices(draft)
            store.write(FirstLaunchState(completed = true))
        }
    }
}
