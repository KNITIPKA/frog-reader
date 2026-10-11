package com.example.frogreader.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.ReaderChromeStyle
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val BOOT_PREFS = "boot_hints"

/** SANS is the stable stored identifier for the bundled IBM Plex Sans family. */
@kotlinx.serialization.Serializable
enum class ReaderFont { LITERATA, SERIF, SANS, CUSTOM }
@kotlinx.serialization.Serializable
enum class ReadingMode { SCROLL, PAGES }

/** Horizontal padding of the reading column. */
@kotlinx.serialization.Serializable
enum class PageMargins { NARROW, NORMAL, WIDE }

/** Page-turn animation style. CASCADE slides a rounded sheet over parallax content. */
@kotlinx.serialization.Serializable
enum class PageTurnAnimation { SLIDE, CASCADE, PAGE_CURL, NONE }

/** One theme for the whole app (every screen and the reading surface). */
@kotlinx.serialization.Serializable
enum class AppTheme { WHITE, SEPIA, OLED }

/** Which light palette is used while the app follows the system appearance. */
@kotlinx.serialization.Serializable
enum class LightThemeDefault { LIGHT, BEIGE }

/** The screen shown after a cold app start. */
@kotlinx.serialization.Serializable
enum class StartupDestination { LIBRARY, LAST_BOOK }

/** How long an unlocked app may remain in the background before locking again. */
@kotlinx.serialization.Serializable
enum class AppLockDelay(val durationMillis: Long) {
    IMMEDIATE(0L),
    ONE_MINUTE(60_000L),
    FIFTEEN_MINUTES(15L * 60_000L),
}

/** How often the app writes a backup by itself. */
@kotlinx.serialization.Serializable
enum class BackupFrequency { OFF, DAILY, WEEKLY }

/** Library card grid or list view mode. */
@kotlinx.serialization.Serializable
enum class LibraryViewMode { GRID, LIST }

/** Settings of the reading surface (font, layout, mode). */
@kotlinx.serialization.Serializable
data class ReaderSettings(
    val fontSizeSp: Float = 18f,
    val lineHeight: Float = 1.5f,
    val font: ReaderFont = ReaderFont.LITERATA,
    /** Path of the user's own font file (ReaderFont.CUSTOM). */
    val customFontPath: String? = null,
    val justify: Boolean = false,
    val hyphenation: Boolean = false,
    /**
     * Render the book the way its publisher designed it: embedded fonts,
     * the book's own alignment, indents, line spacing and hyphenation
     * (where the file actually specifies them) win over the user settings.
     */
    val bookStyles: Boolean = true,
    val pageMargins: PageMargins = PageMargins.WIDE,
    val readingMode: ReadingMode = ReadingMode.PAGES,
    /** Every chapter begins on a fresh page (paged mode). */
    val startChaptersOnNewPage: Boolean = true,
    /** Page footer counts pages left in the chapter instead of showing the page number. */
    val showChapterPagesLeft: Boolean = false,
    /** Show a counter at the bottom of each reading page. */
    val showPageCounter: Boolean = true,
    /** Strip [53]-style footnote markers from the text entirely. */
    val hideFootnotes: Boolean = false,
    /**
     * Invert the colors of the book's pictures. Scans of tables and
     * diagrams are usually black on white and glare in the dark theme;
     * inverted they match the page.
     */
    val invertImages: Boolean = false,
    /**
     * Draw the decorated initials the book's CSS asks for. Publisher's
     * formatting switches these on too — the toggle shows that.
     */
    val dropCaps: Boolean = true,
    /** Chosen page-turn animation in paged mode. */
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.CASCADE,
)

/** App-wide settings (theme, feedback, behavior, privacy). */
@kotlinx.serialization.Serializable
data class AppSettings(
    val theme: AppTheme = AppTheme.WHITE,
    val followSystemTheme: Boolean = true,
    val lightThemeDefault: LightThemeDefault = LightThemeDefault.BEIGE,
    val dynamicColor: Boolean = false,
    val startupDestination: StartupDestination = StartupDestination.LIBRARY,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val volumeKeyPaging: Boolean = true,
    val readerChromeStyle: ReaderChromeStyle = ReaderChromeStyle.FLOATING,
    val autoHideReturnButton: Boolean = true,
    /** Flattened PROCESS_TEXT activity component; null means choose on first use. */
    val defaultTranslator: String? = null,
    val appLock: Boolean = false,
    val appLockDelay: AppLockDelay = AppLockDelay.ONE_MINUTE,
    val backupMode: BackupMode = BackupMode.DATA,
    /** Automatically invert images when OLED theme is active. */
    val autoInvertImages: Boolean = false,
    /** Daily reading goal in minutes (for the stats screen). */
    val dailyGoalMinutes: Int = 30,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val profileName: String = "",
    /** One bounded 512px JPEG, included in existing settings backups. */
    val profileAvatarBase64: String? = null,
    /** Stable mode name; unknown future modes fall back to newest completion in the log UI. */
    val readingLogSort: String = "FINISHED_NEWEST",
)

/** Resolves the palette that should be visible right now. */
fun AppSettings.effectiveTheme(systemDark: Boolean): AppTheme {
    if (!followSystemTheme) return theme
    if (systemDark) return AppTheme.OLED
    return when (lightThemeDefault) {
        LightThemeDefault.LIGHT -> AppTheme.WHITE
        LightThemeDefault.BEIGE -> AppTheme.SEPIA
    }
}

/** Discard the retired override once, without changing any other saved preferences. */
internal object LegacyHeadingPreferenceMigration : DataMigration<Preferences> {
    private val key = booleanPreferencesKey("center_headings")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.contains(key)

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { remove(key) }.toPreferences()

    override suspend fun cleanUp() = Unit
}

/** Package update history also identifies existing readers who never changed a preference. */
private fun Context.installHistory(hasSavedPreferences: Boolean = false): FirstLaunchHistory {
    @Suppress("DEPRECATION")
    val packageInfo = runCatching {
        packageManager.getPackageInfo(packageName, 0)
    }.getOrNull()
    return FirstLaunchHistory(
        hasSavedPreferences = hasSavedPreferences,
        hasSavedAppearance = getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE).all.isNotEmpty(),
        hasReaderData = existingReaderFiles(filesDir),
        wasUpdatedInstall = packageInfo != null &&
            packageInfo.lastUpdateTime > packageInfo.firstInstallTime,
    )
}

private val Context.settingsDataStore by preferencesDataStore(
    name = "reader_settings",
    produceMigrations = { context ->
        listOf(
            NewReaderDefaultsMigration { context.installHistory().isExistingReader },
            LegacyHeadingPreferenceMigration,
        )
    },
)

class SettingsRepository(private val context: Context) {

    private val firstLaunch by lazy {
        FirstLaunchRepository(
            stateFile = File(context.noBackupFilesDir, "welcome_state.json"),
            showOnEveryLaunch = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            hasExistingData = {
                context.installHistory(
                    hasSavedPreferences = context.settingsDataStore.data.first().hasSavedUserPreferences(),
                ).isExistingReader
            },
        )
    }

    suspend fun shouldShowWelcome(completedForLaunch: Boolean = false): Boolean =
        firstLaunch.shouldShowWelcome(completedForLaunch)

    /** Commit only the welcome's choices before recording device-local completion. */
    suspend fun completeWelcome(draft: AppSettings) {
        firstLaunch.finish(draft) { selectedDraft ->
            context.settingsDataStore.edit { prefs ->
                val selected = prefs.readAppSettings().withWelcomeChoices(selectedDraft)
                prefs[Keys.readerChromeStyle] = selected.readerChromeStyle.name
                prefs[Keys.startupDestination] = selected.startupDestination.name
                prefs[Keys.appTheme] = selected.theme.name
                prefs[Keys.followSystemTheme] = selected.followSystemTheme
                prefs[Keys.lightThemeDefault] = selected.lightThemeDefault.name
                prefs[Keys.dynamicColor] = selected.dynamicColor
            }
        }
    }

    private object Keys {
        val fontSize = floatPreferencesKey("font_size_sp")
        val lineHeight = floatPreferencesKey("line_height")
        val font = stringPreferencesKey("font")
        val customFontPath = stringPreferencesKey("custom_font_path")
        val justify = booleanPreferencesKey("justify")
        val hyphenation = booleanPreferencesKey("hyphenation")
        val bookStyles = booleanPreferencesKey("book_styles")
        val pageMargins = stringPreferencesKey("page_margins")
        val readingMode = stringPreferencesKey("reading_mode")
        val chapterNewPage = booleanPreferencesKey("chapter_new_page")
        val chapterPagesLeft = booleanPreferencesKey("chapter_pages_left")
        val showPageCounter = booleanPreferencesKey("show_page_counter")
        val hideFootnotes = booleanPreferencesKey("hide_footnotes")
        val invertImages = booleanPreferencesKey("invert_images")
        val dropCaps = booleanPreferencesKey("drop_caps")
        val pageTurnAnimation = stringPreferencesKey("page_turn_animation")

        // v2: beige became the default; the key bump applies it to installs
        // that still carry the old default in the v1 key.
        val appTheme = stringPreferencesKey("app_theme_v2")
        val followSystemTheme = booleanPreferencesKey("follow_system_theme")
        val lightThemeDefault = stringPreferencesKey("light_theme_default")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val startupDestination = stringPreferencesKey("startup_destination")
        val haptics = booleanPreferencesKey("haptics")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val volumeKeyPaging = booleanPreferencesKey("volume_key_paging")
        val readerChromeStyle = stringPreferencesKey("reader_chrome_style")
        val autoHideReturnButton = booleanPreferencesKey("auto_hide_return_button")
        val defaultTranslator = stringPreferencesKey("default_translator")
        val appLock = booleanPreferencesKey("app_lock")
        val appLockDelay = stringPreferencesKey("app_lock_delay")
        val backupMode = stringPreferencesKey("backup_scope")
        val autoInvertImages = booleanPreferencesKey("auto_invert_images")
        val dailyGoal = androidx.datastore.preferences.core.intPreferencesKey("daily_goal_minutes")
        val profileName = stringPreferencesKey("profile_name")
        val profileAvatar = stringPreferencesKey("profile_avatar")
        val readingLogSort = stringPreferencesKey("reading_log_sort")
        val libraryViewMode = stringPreferencesKey("library_view_mode")
        val deviceId = stringPreferencesKey("device_id")
        val backupFolder = stringPreferencesKey("backup_folder_uri")
        val backupFrequency = stringPreferencesKey("backup_frequency")
        val lastBackupAt = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
    }

    /** The tree Uri of the folder scheduled backups are written to. */
    val backupFolder: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.backupFolder] }

    val backupFrequency: Flow<BackupFrequency> =
        context.settingsDataStore.data.map {
            enumOrDefault(it[Keys.backupFrequency], BackupFrequency.OFF)
        }

    val lastBackupAt: Flow<Long?> =
        context.settingsDataStore.data.map { it[Keys.lastBackupAt] }

    suspend fun setBackupFolder(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            uri?.let { prefs[Keys.backupFolder] = it } ?: prefs.remove(Keys.backupFolder)
        }
    }

    suspend fun setBackupFrequency(frequency: BackupFrequency) {
        context.settingsDataStore.edit { it[Keys.backupFrequency] = frequency.name }
    }

    suspend fun recordBackupAt(millis: Long) {
        context.settingsDataStore.edit { it[Keys.lastBackupAt] = millis }
    }

    /**
     * A UUID minted once, on first ask, and kept for the life of the install.
     *
     * Nothing uses it yet. Syncing two phones needs a way to tell "my edit"
     * from "the other phone's edit", and it has to be stable from before the
     * first sync rather than invented during it. Not derived from any hardware
     * identifier: a restore onto a new phone should carry the same id, and
     * nothing here should be traceable to the device itself.
     */
    suspend fun deviceId(): String {
        context.settingsDataStore.data.first()[Keys.deviceId]?.let { return it }
        val minted = java.util.UUID.randomUUID().toString()
        var result = minted
        context.settingsDataStore.edit { prefs ->
            // Another caller may have won the race between the read and here.
            result = prefs[Keys.deviceId] ?: minted.also { prefs[Keys.deviceId] = it }
        }
        return result
    }

    /** Legacy global reader payload for backup compatibility; live settings belong to each Book. */
    val settings: Flow<ReaderSettings> =
        context.settingsDataStore.data.map { it.readReaderSettings() }

    val appSettings: Flow<AppSettings> =
        context.settingsDataStore.data.map { it.readAppSettings() }

    val libraryViewMode: Flow<LibraryViewMode> =
        context.settingsDataStore.data.map { prefs ->
            enumOrDefault(prefs[Keys.libraryViewMode], LibraryViewMode.GRID)
        }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        updateApp { it.copy(viewMode = mode) }
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(prefs.readReaderSettings())
            prefs[Keys.fontSize] = updated.fontSizeSp
            prefs[Keys.lineHeight] = updated.lineHeight
            prefs[Keys.font] = updated.font.name
            updated.customFontPath
                ?.let { prefs[Keys.customFontPath] = it }
                ?: prefs.remove(Keys.customFontPath)
            prefs[Keys.justify] = updated.justify
            prefs[Keys.hyphenation] = updated.hyphenation
            prefs[Keys.bookStyles] = updated.bookStyles
            prefs[Keys.pageMargins] = updated.pageMargins.name
            prefs[Keys.readingMode] = updated.readingMode.name
            prefs[Keys.chapterNewPage] = updated.startChaptersOnNewPage
            prefs[Keys.chapterPagesLeft] = updated.showChapterPagesLeft
            prefs[Keys.showPageCounter] = updated.showPageCounter
            prefs[Keys.hideFootnotes] = updated.hideFootnotes
            prefs[Keys.invertImages] = updated.invertImages
            prefs[Keys.dropCaps] = updated.dropCaps
            prefs[Keys.pageTurnAnimation] = updated.pageTurnAnimation.name
        }
    }

    suspend fun updateApp(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(prefs.readAppSettings())
            prefs[Keys.appTheme] = updated.theme.name
            prefs[Keys.followSystemTheme] = updated.followSystemTheme
            prefs[Keys.lightThemeDefault] = updated.lightThemeDefault.name
            prefs[Keys.dynamicColor] = updated.dynamicColor
            prefs[Keys.startupDestination] = updated.startupDestination.name
            prefs[Keys.haptics] = updated.haptics
            prefs[Keys.keepScreenOn] = updated.keepScreenOn
            prefs[Keys.volumeKeyPaging] = updated.volumeKeyPaging
            prefs[Keys.readerChromeStyle] = updated.readerChromeStyle.name
            prefs[Keys.autoHideReturnButton] = updated.autoHideReturnButton
            updated.defaultTranslator?.let { prefs[Keys.defaultTranslator] = it }
                ?: prefs.remove(Keys.defaultTranslator)
            prefs[Keys.appLock] = updated.appLock
            prefs[Keys.appLockDelay] = updated.appLockDelay.name
            prefs[Keys.backupMode] = updated.backupMode.name
            prefs[Keys.autoInvertImages] = updated.autoInvertImages
            prefs[Keys.dailyGoal] = updated.dailyGoalMinutes
            prefs[Keys.libraryViewMode] = updated.viewMode.name
            prefs[Keys.profileName] = updated.profileName
            prefs[Keys.readingLogSort] = updated.readingLogSort
            updated.profileAvatarBase64?.let { prefs[Keys.profileAvatar] = it }
                ?: prefs.remove(Keys.profileAvatar)
        }
    }

    private fun Preferences.readReaderSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = this[Keys.fontSize] ?: defaults.fontSizeSp,
            lineHeight = this[Keys.lineHeight] ?: defaults.lineHeight,
            font = enumOrDefault(this[Keys.font], defaults.font),
            customFontPath = this[Keys.customFontPath],
            justify = this[Keys.justify] ?: defaults.justify,
            hyphenation = this[Keys.hyphenation] ?: defaults.hyphenation,
            bookStyles = this[Keys.bookStyles] ?: defaults.bookStyles,
            pageMargins = enumOrDefault(this[Keys.pageMargins], defaults.pageMargins),
            readingMode = enumOrDefault(this[Keys.readingMode], defaults.readingMode),
            startChaptersOnNewPage = this[Keys.chapterNewPage] ?: defaults.startChaptersOnNewPage,
            showChapterPagesLeft = this[Keys.chapterPagesLeft] ?: defaults.showChapterPagesLeft,
            showPageCounter = this[Keys.showPageCounter] ?: defaults.showPageCounter,
            hideFootnotes = this[Keys.hideFootnotes] ?: defaults.hideFootnotes,
            invertImages = this[Keys.invertImages] ?: defaults.invertImages,
            dropCaps = this[Keys.dropCaps] ?: defaults.dropCaps,
            pageTurnAnimation = enumOrDefault(
                this[Keys.pageTurnAnimation],
                defaults.pageTurnAnimation,
            ),
        )
    }

    private fun Preferences.readAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            theme = enumOrDefault(this[Keys.appTheme], defaults.theme),
            followSystemTheme = this[Keys.followSystemTheme] ?: defaults.followSystemTheme,
            lightThemeDefault = enumOrDefault(
                this[Keys.lightThemeDefault],
                defaults.lightThemeDefault,
            ),
            dynamicColor = this[Keys.dynamicColor] ?: defaults.dynamicColor,
            startupDestination = enumOrDefault(
                this[Keys.startupDestination],
                defaults.startupDestination,
            ),
            haptics = this[Keys.haptics] ?: defaults.haptics,
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            volumeKeyPaging = this[Keys.volumeKeyPaging] ?: defaults.volumeKeyPaging,
            readerChromeStyle = enumOrDefault(this[Keys.readerChromeStyle], defaults.readerChromeStyle),
            autoHideReturnButton = this[Keys.autoHideReturnButton] ?: defaults.autoHideReturnButton,
            defaultTranslator = this[Keys.defaultTranslator],
            appLock = this[Keys.appLock] ?: defaults.appLock,
            appLockDelay = enumOrDefault(this[Keys.appLockDelay], defaults.appLockDelay),
            backupMode = enumOrDefault(this[Keys.backupMode], defaults.backupMode),
            autoInvertImages = this[Keys.autoInvertImages] ?: defaults.autoInvertImages,
            dailyGoalMinutes = this[Keys.dailyGoal] ?: defaults.dailyGoalMinutes,
            viewMode = enumOrDefault(this[Keys.libraryViewMode], defaults.viewMode),
            profileName = this[Keys.profileName] ?: defaults.profileName,
            profileAvatarBase64 = this[Keys.profileAvatar],
            readingLogSort = this[Keys.readingLogSort] ?: defaults.readingLogSort,
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        private const val KEY_THEME = "app_theme"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        /**
         * The appearance the app was last running in, readable synchronously.
         *
         * DataStore's first read is disk I/O on a background dispatcher, so the
         * first frame after a cold start would otherwise have to be painted in
         * the default theme and corrected a moment later. On a Midnight install
         * that means a beige screen flashing black; with Material You it would
         * similarly flash the fixed palette. This mirror is written from the UI
         * whenever the effective appearance is known.
         */
        fun bootTheme(context: Context): AppTheme {
            val name = context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
            return name?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppSettings().effectiveTheme(
                    systemDark = context.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
                )
        }

        fun bootDynamicColor(context: Context): Boolean =
            context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DYNAMIC_COLOR, AppSettings().dynamicColor)

        fun rememberBootAppearance(
            context: Context,
            theme: AppTheme,
            dynamicColor: Boolean,
        ) {
            if (bootTheme(context) == theme && bootDynamicColor(context) == dynamicColor) return
            context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, theme.name)
                .putBoolean(KEY_DYNAMIC_COLOR, dynamicColor)
                .apply()
        }
    }
}
