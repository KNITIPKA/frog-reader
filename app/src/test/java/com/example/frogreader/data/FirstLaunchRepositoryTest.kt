package com.example.frogreader.data

import com.example.frogreader.data.model.ReaderChromeStyle
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FirstLaunchRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun repository(
        file: File,
        existing: Boolean = false,
        showOnEveryLaunch: Boolean = false,
    ) = FirstLaunchRepository(file, showOnEveryLaunch) { existing }

    @Test
    fun `fresh install persists pending and resumes welcome after recreation`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        assertTrue(repository(file).shouldShowWelcome())
        // Preview writes or partial preference saves must not turn a pending first launch
        // into a legacy install, even when the Activity and repository are recreated.
        assertTrue(repository(file, existing = true).shouldShowWelcome())
    }

    @Test
    fun `existing reader is migrated once and never receives welcome`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        assertFalse(repository(file, existing = true).shouldShowWelcome())
        assertFalse(repository(file).shouldShowWelcome())
    }

    @Test
    fun `completion survives recreation and release changes without checking history`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        val first = repository(file)
        assertTrue(first.shouldShowWelcome())
        first.finish()
        val reopened = FirstLaunchRepository(file) { error("Completion should not inspect install history") }
        assertFalse(reopened.shouldShowWelcome())
    }

    @Test
    fun `debug completion persists but only dismisses welcome for the current launch`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        val first = repository(file, showOnEveryLaunch = true)
        assertTrue(first.shouldShowWelcome())
        first.finish(AppSettings())
        // MainActivity restores this completion with rememberSaveable on recreation or
        // task restoration, keeping its navigation rather than reopening Welcome.
        assertFalse(repository(file, showOnEveryLaunch = true)
            .shouldShowWelcome(completedForLaunch = true))
        // A new launch has no restored Activity completion and replays debug Welcome.
        assertTrue(repository(file, showOnEveryLaunch = true).shouldShowWelcome())
        // Debug completion still writes the marker; release does not repeat onboarding.
        assertFalse(repository(file).shouldShowWelcome())
    }

    @Test
    fun `existing debug readers replay welcome and retain release migration`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        assertTrue(repository(file, existing = true, showOnEveryLaunch = true).shouldShowWelcome())
        assertFalse(repository(file).shouldShowWelcome())
        assertTrue(repository(file, showOnEveryLaunch = true).shouldShowWelcome())
    }

    @Test
    fun `completed activity restoration skips welcome persistence checks`() = runTest {
        val stateDirectory = folder.newFolder("unreadable-state-file")
        val restored = FirstLaunchRepository(stateDirectory, showOnEveryLaunch = true) {
            error("A completed launch must not inspect install history")
        }
        assertFalse(restored.shouldShowWelcome(completedForLaunch = true))
    }

    @Test
    fun `completion without draft leaves saved preferences alone`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        var choicesApplied = false
        assertTrue(repository(file).shouldShowWelcome())
        repository(file).finish(draft = null) { choicesApplied = true }
        assertFalse(choicesApplied)
        assertFalse(repository(file).shouldShowWelcome())
    }

    @Test
    fun `failed preference save keeps welcome pending and retry applies choices`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        val first = repository(file)
        assertTrue(first.shouldShowWelcome())
        try {
            first.finish(AppSettings()) { throw IOException("Preference write failed") }
            error("Expected failure")
        } catch (_: IOException) {
            assertTrue(repository(file, existing = true).shouldShowWelcome())
        }
        var saved = AppSettings()
        val draft = saved.copy(readerChromeStyle = ReaderChromeStyle.REGULAR)
        repository(file).finish(draft) { saved = saved.withWelcomeChoices(it) }
        assertEquals(ReaderChromeStyle.REGULAR, saved.readerChromeStyle)
        assertFalse(repository(file).shouldShowWelcome())
    }

    @Test
    fun `failed completion write after saving choices retains pending across recreation`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        val first = repository(file)
        assertTrue(first.shouldShowWelcome())
        var saved = AppSettings()
        val draft = saved.copy(startupDestination = StartupDestination.LAST_BOOK)
        try {
            first.finish(draft) {
                saved = saved.withWelcomeChoices(it)
                // Block the atomic temporary file after preference persistence. The live
                // pending marker remains readable when completion cannot be written.
                assertTrue(File(folder.root, "welcome_state.json.tmp").mkdir())
            }
            error("Expected failure")
        } catch (_: IOException) {
            assertEquals(StartupDestination.LAST_BOOK, saved.startupDestination)
        }
        assertTrue(File(folder.root, "welcome_state.json.tmp").delete())
        assertTrue(repository(file, existing = true).shouldShowWelcome())
        repository(file).finish()
        assertFalse(repository(file).shouldShowWelcome())
    }

    @Test
    fun `first completion writes pending before attempting preferences`() = runTest {
        val file = File(folder.root, "welcome_state.json")
        try {
            repository(file).finish(AppSettings()) { throw IOException("Preference write failed") }
            error("Expected failure")
        } catch (_: IOException) {
            assertTrue(repository(file, existing = true).shouldShowWelcome())
        }
    }

    @Test
    fun `default old installs are recognized by package update history`() {
        assertFalse(FirstLaunchHistory().isExistingReader)
        assertTrue(FirstLaunchHistory(wasUpdatedInstall = true).isExistingReader)
        assertTrue(FirstLaunchHistory(hasSavedPreferences = true).isExistingReader)
        assertTrue(FirstLaunchHistory(hasSavedAppearance = true).isExistingReader)
        assertTrue(FirstLaunchHistory(hasReaderData = true).isExistingReader)
    }

    @Test
    fun `empty libraries and recoverable backup files count as prior use`() {
        assertFalse(existingReaderFiles(folder.root))
        File(folder.root, "books").mkdirs()
        assertFalse(existingReaderFiles(folder.root))
        val index = File(folder.root, "library.json.bak")
        index.writeText("{}")
        assertTrue(existingReaderFiles(folder.root))
        index.delete()
        File(folder.root, "books/book.epub").writeText("content")
        assertTrue(existingReaderFiles(folder.root))
    }

    @Test
    fun `continue merges only welcome fields into latest unrelated settings`() {
        val initial = AppSettings(lightThemeDefault = LightThemeDefault.LIGHT)
        val draft = initial.copy(
            theme = AppTheme.SEPIA,
            followSystemTheme = false,
            lightThemeDefault = LightThemeDefault.BEIGE,
            startupDestination = StartupDestination.LAST_BOOK,
            readerChromeStyle = ReaderChromeStyle.REGULAR,
            haptics = true,
            dynamicColor = false,
            profileName = "Stale name",
        )
        val latest = initial.copy(
            haptics = false,
            dynamicColor = true,
            profileName = "Latest name",
            profileAvatarBase64 = "avatar",
            appLock = true,
            dailyGoalMinutes = 45,
        )
        assertEquals(
            latest.copy(
                theme = draft.theme,
                followSystemTheme = draft.followSystemTheme,
                lightThemeDefault = draft.lightThemeDefault,
                dynamicColor = draft.dynamicColor,
                startupDestination = draft.startupDestination,
                readerChromeStyle = draft.readerChromeStyle,
            ),
            latest.withWelcomeChoices(draft),
        )
    }

    @Test
    fun `welcome marker is absent from serialized app settings used by backups`() {
        val encoded = Json.Default.encodeToString(AppSettings.serializer(), AppSettings())
        assertFalse(encoded.contains("welcome"))
        assertFalse(encoded.contains("completed"))
        assertFalse(encoded.contains("firstLaunch"))
    }
}
