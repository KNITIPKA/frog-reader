package com.example.frogreader.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.frogreader.data.db.DatabaseManager
import com.example.frogreader.data.model.BackupSettings
import com.example.frogreader.data.model.BackupMode
import com.example.frogreader.data.model.BookFormat
import com.example.frogreader.data.model.ProfileSearchCategory
import com.example.frogreader.data.model.ReaderChromeStyle
import com.example.frogreader.data.model.PdfToolbarAppearance
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BOOT_PREFS = "boot_hints"

/** SANS is the stable stored identifier for the bundled IBM Plex Sans family. */
@kotlinx.serialization.Serializable
enum class ReaderFont { LITERATA, SERIF, SANS, CUSTOM }
@kotlinx.serialization.Serializable
enum class ReadingMode { SCROLL, PAGES }

/** Legacy reading-column presets, retained for old books and backups. */
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
    /** Chosen instance coordinates, or explicit publisher axes retained by an adopted Auto family. */
    val customFontAxes: Map<String, Float> = emptyMap(),
    /** False keeps every existing manual face selection unchanged. */
    val customFontAuto: Boolean = false,
    /** Files belonging to the selected Auto family, refreshed when the library changes. */
    val customFontFamilyPaths: List<String> = emptyList(),
    /** Legacy preference retained for old settings; reading now adapts automatically. */
    val fontOpticalSizing: Boolean = true,
    /** Legacy manual width preference; reading now uses the normal font width by default. */
    val fontStretchPercent: Float = 100f,
    val justify: Boolean = false,
    val hyphenation: Boolean = false,
    /**
     * Render the book the way its publisher designed it: embedded fonts,
     * the book's own alignment, indents, line spacing and hyphenation
     * (where the file actually specifies them) win over the user settings.
     */
    val bookStyles: Boolean = true,
    val pageMargins: PageMargins = PageMargins.WIDE,
    /** Horizontal inset in dp; null preserves the saved legacy preset. */
    val pageMarginDp: Int? = null,
    val readingMode: ReadingMode = ReadingMode.PAGES,
    /** Every chapter begins on a fresh page (paged mode). */
    val startChaptersOnNewPage: Boolean = true,
    /** Page footer counts pages left in the chapter instead of showing the page number. */
    val showChapterPagesLeft: Boolean = false,
    /** Show a counter at the bottom of each reading page. */
    val showPageCounter: Boolean = true,
    val showTotalPageCount: Boolean = false,
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
    /** PDF-specific optional single-page mode; continuous scrolling remains the default. */
    val pdfSinglePage: Boolean = false,
    /** The book's PDF reader palette; null follows the app until a theme is chosen. */
    @kotlinx.serialization.SerialName("pdfBackground")
    val pdfTheme: AppTheme? = null,
) {
    /** Retire hidden manual controls without changing selected fonts or publisher CSS. */
    fun withAutomaticTypography(): ReaderSettings =
        if (fontOpticalSizing && fontStretchPercent == 100f) this
        else copy(fontOpticalSizing = true, fontStretchPercent = 100f)

    /** Reading and pagination use the same bounded inset, including restored settings. */
    val sideMarginDp: Int
        get() = (pageMarginDp ?: when (pageMargins) {
            PageMargins.NARROW -> 10
            PageMargins.NORMAL -> 20
            PageMargins.WIDE -> 34
        }).coerceIn(MIN_SIDE_MARGIN_DP, MAX_SIDE_MARGIN_DP)

    /** Converts absolute custom font paths inside [fontsDir] into portable `fonts/<name>` entries. */
    fun relativizeCustomFontPaths(fontsDir: File?): ReaderSettings {
        if (fontsDir == null) return this
        val canonicalFontsDir = runCatching { fontsDir.canonicalFile }.getOrElse { fontsDir.absoluteFile }
        fun relativize(raw: String): String {
            if (raw.startsWith("fonts/") && '/' !in raw.removePrefix("fonts/") && '\\' !in raw &&
                raw.removePrefix("fonts/") !in listOf("", ".", "..")
            ) {
                return raw
            }
            val file = File(raw)
            val parent = runCatching { file.canonicalFile.parentFile }.getOrElse { file.absoluteFile.parentFile }
            return if (parent == canonicalFontsDir && file.name.isNotBlank()) {
                "fonts/${file.name}"
            } else {
                raw
            }
        }
        val nextCustom = customFontPath?.let(::relativize)
        val nextFamily = customFontFamilyPaths.map(::relativize)
        return if (nextCustom == customFontPath && nextFamily == customFontFamilyPaths) this
        else copy(customFontPath = nextCustom, customFontFamilyPaths = nextFamily)
    }

    /** Rebases portable `fonts/<name>` or cross-device font paths onto [fontsDir] when present. */
    fun rebaseCustomFontPaths(fontsDir: File?, restoredNames: Map<String, String> = emptyMap()): ReaderSettings {
        if (fontsDir == null) return this
        fun rebase(raw: String): String {
            val isPortable = raw.startsWith("fonts/")
            val leaf = if (isPortable) raw.removePrefix("fonts/") else File(raw).name
            val safeLeaf = leaf.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".." }
            if (safeLeaf != null) {
                val candidate = File(fontsDir, restoredNames[safeLeaf] ?: safeLeaf)
                if (candidate.exists() || isPortable) return candidate.absolutePath
            }
            return raw
        }
        val nextCustom = customFontPath?.let(::rebase)
        val nextFamily = customFontFamilyPaths.map(::rebase)
        return if (nextCustom == customFontPath && nextFamily == customFontFamilyPaths) this
        else copy(customFontPath = nextCustom, customFontFamilyPaths = nextFamily)
    }

    companion object {
        const val MIN_SIDE_MARGIN_DP = 8
        const val MAX_SIDE_MARGIN_DP = 48
        const val SIDE_MARGIN_STEP_DP = 2

        /** Default reading settings for [format], before the book saves its own. */
        fun defaultFor(format: BookFormat?): ReaderSettings = when (format) {
            BookFormat.TXT, BookFormat.MD -> ReaderSettings(
                fontSizeSp = 15f,
                lineHeight = 1.5f,
                font = ReaderFont.SANS,
                justify = false,
                hyphenation = false,
                pageMarginDp = 14,
                readingMode = ReadingMode.SCROLL,
            )
            else -> ReaderSettings()
        }
    }
}

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
    val pdfToolbarAppearance: PdfToolbarAppearance = PdfToolbarAppearance.GLASS,
    val showPdfTopBar: Boolean = true,
    val showCollapsedReaderHandle: Boolean = false,
    val expandReaderPanelsOnSwipe: Boolean = false,
    val brightnessSwipeEnabled: Boolean = false,
    val autoHideReturnButton: Boolean = true,
    val showReaderLoadHud: Boolean = false,
    /** Handler ID; legacy flattened PROCESS_TEXT components remain valid. Null chooses on first use. */
    val defaultTranslator: String? = null,
    /** External dictionary target identifier; null means choose on first use. */
    val defaultDictionary: String? = null,
    /** External search target identifier; null means choose on first use. */
    val defaultSearchApp: String? = null,
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
    /** User-customized order of categories in Profile Global Search. */
    val profileSearchCategoryOrder: List<ProfileSearchCategory> = ProfileSearchCategory.DEFAULT_ORDER,
    /** Categories hidden via the eye toggle in Profile Global Search. */
    val profileSearchHiddenCategories: Set<ProfileSearchCategory> = emptySet(),
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
    produceMigrations = {
        listOf(
            LegacyHeadingPreferenceMigration,
        )
    },
)

class SettingsRepository(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.settingsDataStore,
) {

    private val restoreSettingsMutex = Mutex()
    @Volatile private var restoreRecoveryChecked = false
    private val restoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val recoveredPreferences: Flow<Preferences> =
        preferences.data.onStart { ensureRestoreRecovered() }

    /** Recover the preference half of an interrupted database/settings restore before exposure. */
    internal suspend fun ensureRestoreRecovered() {
        if (restoreRecoveryChecked) return
        withContext(Dispatchers.IO) {
            restoreSettingsMutex.withLock {
                if (restoreRecoveryChecked) return@withLock
                // Opening the database first rolls an interrupted swap back and creates the
                // marker that distinguishes rollback from a committed restore.
                DatabaseManager.withSwapLock(context.filesDir) { }
                val previous = DatabaseManager.restoreSettingsBackupFileFor(context.filesDir)
                val rollback = DatabaseManager.restoreSettingsRollbackMarkerFileFor(context.filesDir)
                if (rollback.exists()) {
                    if (!previous.isFile) throw IOException("Previous settings are missing for restore rollback.")
                    writeBackupSettings(restoreJson.decodeFromString<BackupSettings>(previous.readText()))
                }
                clearRestoreSettingsFiles()
                restoreRecoveryChecked = true
            }
        }
    }

    /** Read both groups from one immutable DataStore snapshot for a consistent archive payload. */
    internal suspend fun snapshotForBackup(): BackupSettings {
        ensureRestoreRecovered()
        return readBackupSettings()
    }

    private suspend fun readBackupSettings(): BackupSettings {
        val prefs = preferences.data.first()
        return BackupSettings(prefs.readReaderSettings(), prefs.readAppSettings())
    }

    /** Durably retain the current model before either restored preference group is written. */
    internal suspend fun prepareRestoreSettings() {
        ensureRestoreRecovered()
        withContext(Dispatchers.IO) {
            restoreSettingsMutex.withLock {
                val previous = DatabaseManager.restoreSettingsBackupFileFor(context.filesDir)
                if (previous.exists()) throw IOException("A previous settings restore is still pending.")
                val saved = readBackupSettings()
                previous.parentFile?.mkdirs()
                val temporary = File(previous.parentFile, "${previous.name}.tmp")
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(restoreJson.encodeToString(saved).encodeToByteArray())
                        output.fd.sync()
                    }
                    Files.move(temporary.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    temporary.delete()
                }
            }
        }
    }

    /** Both groups share one DataStore transaction, so readers never see half an import. */
    internal suspend fun applyBackupSettings(settings: BackupSettings) {
        ensureRestoreRecovered()
        restoreSettingsMutex.withLock { writeBackupSettings(settings) }
    }

    private suspend fun writeBackupSettings(settings: BackupSettings) {
        preferences.edit { prefs ->
            writeReaderSettings(prefs, settings.reader)
            writeAppSettings(prefs, settings.app)
        }
    }

    internal suspend fun finishRestoreSettings() = withContext(Dispatchers.IO) {
        restoreSettingsMutex.withLock { clearRestoreSettingsFiles() }
    }

    internal suspend fun rollbackRestoreSettings() = withContext(Dispatchers.IO) {
        restoreSettingsMutex.withLock {
            val previous = DatabaseManager.restoreSettingsBackupFileFor(context.filesDir)
            val rollback = DatabaseManager.restoreSettingsRollbackMarkerFileFor(context.filesDir)
            val database = DatabaseManager.databaseFileFor(context.filesDir)
            val uncommittedDatabase = File(database.parentFile, "${database.name}.pre_restore_bak")
            if (rollback.exists() || uncommittedDatabase.exists()) {
                if (!previous.isFile) throw IOException("Previous settings are missing for restore rollback.")
                writeBackupSettings(restoreJson.decodeFromString<BackupSettings>(previous.readText()))
            }
            clearRestoreSettingsFiles()
        }
    }

    private fun clearRestoreSettingsFiles() {
        // Remove the marker first: a crash after preferences are repaired may safely discard
        // the remaining snapshot, whereas a marker without its snapshot is unrecoverable.
        for (file in listOf(
            DatabaseManager.restoreSettingsRollbackMarkerFileFor(context.filesDir),
            DatabaseManager.restoreSettingsBackupFileFor(context.filesDir),
        )) {
            if (file.exists() && !file.delete()) throw IOException("Could not remove ${file.name}")
        }
    }

    private val firstLaunch by lazy {
        FirstLaunchRepository(
            stateFile = File(context.noBackupFilesDir, "welcome_state.json"),
        )
    }

    suspend fun shouldShowWelcome(completedForLaunch: Boolean = false): Boolean =
        firstLaunch.shouldShowWelcome(completedForLaunch)

    /** Commit only the welcome's choices before recording device-local completion. */
    suspend fun completeWelcome(draft: AppSettings) {
        ensureRestoreRecovered()
        firstLaunch.finish(draft) { selectedDraft ->
            preferences.edit { prefs ->
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
        val customFontAxes = stringPreferencesKey("custom_font_axes")
        val customFontAuto = booleanPreferencesKey("custom_font_auto")
        val customFontFamilyPaths = stringPreferencesKey("custom_font_family_paths")
        val fontOpticalSizing = booleanPreferencesKey("font_optical_sizing")
        val fontStretchPercent = floatPreferencesKey("font_stretch_percent")
        val justify = booleanPreferencesKey("justify")
        val hyphenation = booleanPreferencesKey("hyphenation")
        val bookStyles = booleanPreferencesKey("book_styles")
        val pageMargins = stringPreferencesKey("page_margins")
        val pageMarginDp = intPreferencesKey("page_margin_dp")
        val readingMode = stringPreferencesKey("reading_mode")
        val chapterNewPage = booleanPreferencesKey("chapter_new_page")
        val chapterPagesLeft = booleanPreferencesKey("chapter_pages_left")
        val showPageCounter = booleanPreferencesKey("show_page_counter")
        val showTotalPageCount = booleanPreferencesKey("show_total_page_count")
        val hideFootnotes = booleanPreferencesKey("hide_footnotes")
        val invertImages = booleanPreferencesKey("invert_images")
        val dropCaps = booleanPreferencesKey("drop_caps")
        val pageTurnAnimation = stringPreferencesKey("page_turn_animation")
        val pdfSinglePage = booleanPreferencesKey("pdf_single_page")
        val pdfTheme = stringPreferencesKey("pdf_background")

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
        val pdfToolbarAppearance = stringPreferencesKey("pdf_toolbar_appearance")
        val showPdfTopBar = booleanPreferencesKey("show_pdf_top_bar")
        val showCollapsedReaderHandle = booleanPreferencesKey("show_collapsed_reader_handle")
        val expandReaderPanelsOnSwipe = booleanPreferencesKey("expand_reader_panels_on_swipe")
        val brightnessSwipeEnabled = booleanPreferencesKey("brightness_swipe_enabled")
        val autoHideReturnButton = booleanPreferencesKey("auto_hide_return_button")
        val showReaderLoadHud = booleanPreferencesKey("show_reader_load_hud")
        val defaultTranslator = stringPreferencesKey("default_translator")
        val defaultDictionary = stringPreferencesKey("default_dictionary")
        val defaultSearchApp = stringPreferencesKey("default_search_app")
        val appLock = booleanPreferencesKey("app_lock")
        val appLockDelay = stringPreferencesKey("app_lock_delay")
        val backupMode = stringPreferencesKey("backup_scope")
        val autoInvertImages = booleanPreferencesKey("auto_invert_images")
        val dailyGoal = androidx.datastore.preferences.core.intPreferencesKey("daily_goal_minutes")
        val profileName = stringPreferencesKey("profile_name")
        val profileAvatar = stringPreferencesKey("profile_avatar")
        val readingLogSort = stringPreferencesKey("reading_log_sort")
        val profileSearchCategoryOrder = stringPreferencesKey("profile_search_category_order")
        val profileSearchHiddenCategories = stringPreferencesKey("profile_search_hidden_categories")
        val libraryViewMode = stringPreferencesKey("library_view_mode")
        val deviceId = stringPreferencesKey("device_id")
        val backupFolder = stringPreferencesKey("backup_folder_uri")
        val backupFrequency = stringPreferencesKey("backup_frequency")
        val lastBackupAt = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
    }

    /** The tree Uri of the folder scheduled backups are written to. */
    val backupFolder: Flow<String?> =
        recoveredPreferences.map { it[Keys.backupFolder] }

    val backupFrequency: Flow<BackupFrequency> =
        recoveredPreferences.map {
            enumOrDefault(it[Keys.backupFrequency], BackupFrequency.OFF)
        }

    val lastBackupAt: Flow<Long?> =
        recoveredPreferences.map { it[Keys.lastBackupAt] }

    suspend fun setBackupFolder(uri: String?) {
        ensureRestoreRecovered()
        preferences.edit { prefs ->
            uri?.let { prefs[Keys.backupFolder] = it } ?: prefs.remove(Keys.backupFolder)
        }
    }

    suspend fun setBackupFrequency(frequency: BackupFrequency) {
        ensureRestoreRecovered()
        preferences.edit { it[Keys.backupFrequency] = frequency.name }
    }

    suspend fun recordBackupAt(millis: Long) {
        ensureRestoreRecovered()
        preferences.edit { it[Keys.lastBackupAt] = millis }
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
        ensureRestoreRecovered()
        preferences.data.first()[Keys.deviceId]?.let { return it }
        val minted = java.util.UUID.randomUUID().toString()
        var result = minted
        preferences.edit { prefs ->
            // Another caller may have won the race between the read and here.
            result = prefs[Keys.deviceId] ?: minted.also { prefs[Keys.deviceId] = it }
        }
        return result
    }

    /** Legacy global reader payload for backup compatibility; live settings belong to each Book. */
    val settings: Flow<ReaderSettings> =
        recoveredPreferences.map { it.readReaderSettings() }

    val appSettings: Flow<AppSettings> =
        recoveredPreferences.map { it.readAppSettings() }

    val libraryViewMode: Flow<LibraryViewMode> =
        recoveredPreferences.map { prefs ->
            enumOrDefault(prefs[Keys.libraryViewMode], LibraryViewMode.GRID)
        }

    suspend fun setLibraryViewMode(mode: LibraryViewMode) {
        updateApp { it.copy(viewMode = mode) }
    }

    suspend fun setShowReaderLoadHud(enabled: Boolean) {
        updateApp { it.copy(showReaderLoadHud = enabled) }
    }

    suspend fun setProfileSearchCategories(
        order: List<ProfileSearchCategory>,
        hidden: Set<ProfileSearchCategory>,
    ) {
        updateApp {
            it.copy(
                profileSearchCategoryOrder = ProfileSearchCategory.normalizeOrder(order),
                profileSearchHiddenCategories = hidden,
            )
        }
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        ensureRestoreRecovered()
        preferences.edit { prefs ->
            writeReaderSettings(prefs, transform(prefs.readReaderSettings()))
        }
    }

    suspend fun updateApp(transform: (AppSettings) -> AppSettings) {
        ensureRestoreRecovered()
        preferences.edit { prefs ->
            writeAppSettings(prefs, transform(prefs.readAppSettings()))
        }
    }

    private fun writeReaderSettings(prefs: MutablePreferences, settings: ReaderSettings) {
        val updated = settings.withAutomaticTypography()
        prefs[Keys.fontSize] = updated.fontSizeSp
        prefs[Keys.lineHeight] = updated.lineHeight
        prefs[Keys.font] = updated.font.name
        updated.customFontPath
            ?.let { prefs[Keys.customFontPath] = it }
            ?: prefs.remove(Keys.customFontPath)
        val axes = normalizedFontAxes(updated.customFontAxes)
        if (axes.isEmpty()) prefs.remove(Keys.customFontAxes)
        else prefs[Keys.customFontAxes] = Json.encodeToString(axes)
        prefs[Keys.customFontAuto] = updated.customFontAuto
        if (updated.customFontFamilyPaths.isEmpty()) prefs.remove(Keys.customFontFamilyPaths)
        else prefs[Keys.customFontFamilyPaths] = Json.encodeToString(updated.customFontFamilyPaths.distinct().sorted())
        prefs[Keys.fontOpticalSizing] = updated.fontOpticalSizing
        prefs[Keys.fontStretchPercent] = updated.fontStretchPercent.takeIf { it.isFinite() && it > 0f } ?: 100f
        prefs[Keys.justify] = updated.justify
        prefs[Keys.hyphenation] = updated.hyphenation
        prefs[Keys.bookStyles] = updated.bookStyles
        prefs[Keys.pageMargins] = updated.pageMargins.name
        updated.pageMarginDp
            ?.let { prefs[Keys.pageMarginDp] = updated.sideMarginDp }
            ?: prefs.remove(Keys.pageMarginDp)
        prefs[Keys.readingMode] = updated.readingMode.name
        prefs[Keys.chapterNewPage] = updated.startChaptersOnNewPage
        prefs[Keys.chapterPagesLeft] = updated.showChapterPagesLeft
        prefs[Keys.showPageCounter] = updated.showPageCounter
        prefs[Keys.showTotalPageCount] = updated.showTotalPageCount
        prefs[Keys.hideFootnotes] = updated.hideFootnotes
        prefs[Keys.invertImages] = updated.invertImages
        prefs[Keys.dropCaps] = updated.dropCaps
        prefs[Keys.pageTurnAnimation] = updated.pageTurnAnimation.name
        prefs[Keys.pdfSinglePage] = updated.pdfSinglePage
        updated.pdfTheme?.let { prefs[Keys.pdfTheme] = it.name }
            ?: prefs.remove(Keys.pdfTheme)
    }

    private fun writeAppSettings(prefs: MutablePreferences, updated: AppSettings) {
        prefs[Keys.appTheme] = updated.theme.name
        prefs[Keys.followSystemTheme] = updated.followSystemTheme
        prefs[Keys.lightThemeDefault] = updated.lightThemeDefault.name
        prefs[Keys.dynamicColor] = updated.dynamicColor
        prefs[Keys.startupDestination] = updated.startupDestination.name
        prefs[Keys.haptics] = updated.haptics
        prefs[Keys.keepScreenOn] = updated.keepScreenOn
        prefs[Keys.volumeKeyPaging] = updated.volumeKeyPaging
        prefs[Keys.readerChromeStyle] = updated.readerChromeStyle.name
        prefs[Keys.pdfToolbarAppearance] = updated.pdfToolbarAppearance.name
        prefs[Keys.showPdfTopBar] = updated.showPdfTopBar
        prefs[Keys.showCollapsedReaderHandle] = updated.showCollapsedReaderHandle
        prefs[Keys.expandReaderPanelsOnSwipe] = updated.expandReaderPanelsOnSwipe
        prefs[Keys.brightnessSwipeEnabled] = updated.brightnessSwipeEnabled
        prefs[Keys.autoHideReturnButton] = updated.autoHideReturnButton
        prefs[Keys.showReaderLoadHud] = updated.showReaderLoadHud
        updated.defaultTranslator?.let { prefs[Keys.defaultTranslator] = it }
            ?: prefs.remove(Keys.defaultTranslator)
        updated.defaultDictionary?.let { prefs[Keys.defaultDictionary] = it }
            ?: prefs.remove(Keys.defaultDictionary)
        updated.defaultSearchApp?.let { prefs[Keys.defaultSearchApp] = it }
            ?: prefs.remove(Keys.defaultSearchApp)
        prefs[Keys.appLock] = updated.appLock
        prefs[Keys.appLockDelay] = updated.appLockDelay.name
        prefs[Keys.backupMode] = updated.backupMode.name
        prefs[Keys.autoInvertImages] = updated.autoInvertImages
        prefs[Keys.dailyGoal] = updated.dailyGoalMinutes
        prefs[Keys.libraryViewMode] = updated.viewMode.name
        prefs[Keys.profileName] = updated.profileName
        prefs[Keys.readingLogSort] = updated.readingLogSort
        val normalizedOrder = ProfileSearchCategory.normalizeOrder(updated.profileSearchCategoryOrder)
        if (normalizedOrder == ProfileSearchCategory.DEFAULT_ORDER) {
            prefs.remove(Keys.profileSearchCategoryOrder)
        } else {
            prefs[Keys.profileSearchCategoryOrder] = normalizedOrder.joinToString(",") { it.name }
        }
        if (updated.profileSearchHiddenCategories.isEmpty()) {
            prefs.remove(Keys.profileSearchHiddenCategories)
        } else {
            prefs[Keys.profileSearchHiddenCategories] =
                updated.profileSearchHiddenCategories.map { it.name }.sorted().joinToString(",")
        }
        updated.profileAvatarBase64?.let { prefs[Keys.profileAvatar] = it }
            ?: prefs.remove(Keys.profileAvatar)
    }

    private fun Preferences.readReaderSettings(): ReaderSettings {
        val defaults = ReaderSettings()
        return ReaderSettings(
            fontSizeSp = this[Keys.fontSize] ?: defaults.fontSizeSp,
            lineHeight = this[Keys.lineHeight] ?: defaults.lineHeight,
            font = enumOrDefault(this[Keys.font], defaults.font),
            customFontPath = this[Keys.customFontPath],
            customFontAxes = this[Keys.customFontAxes]?.let { saved ->
                runCatching { normalizedFontAxes(Json.decodeFromString<Map<String, Float>>(saved)) }.getOrDefault(emptyMap())
            }.orEmpty(),
            customFontAuto = this[Keys.customFontAuto] ?: defaults.customFontAuto,
            customFontFamilyPaths = this[Keys.customFontFamilyPaths]?.let { saved ->
                runCatching { Json.decodeFromString<List<String>>(saved).distinct().sorted() }.getOrDefault(emptyList())
            }.orEmpty(),
            fontOpticalSizing = defaults.fontOpticalSizing,
            fontStretchPercent = defaults.fontStretchPercent,
            justify = this[Keys.justify] ?: defaults.justify,
            hyphenation = this[Keys.hyphenation] ?: defaults.hyphenation,
            bookStyles = this[Keys.bookStyles] ?: defaults.bookStyles,
            pageMargins = enumOrDefault(this[Keys.pageMargins], defaults.pageMargins),
            pageMarginDp = this[Keys.pageMarginDp]?.coerceIn(
                ReaderSettings.MIN_SIDE_MARGIN_DP,
                ReaderSettings.MAX_SIDE_MARGIN_DP,
            ),
            readingMode = enumOrDefault(this[Keys.readingMode], defaults.readingMode),
            startChaptersOnNewPage = this[Keys.chapterNewPage] ?: defaults.startChaptersOnNewPage,
            showChapterPagesLeft = this[Keys.chapterPagesLeft] ?: defaults.showChapterPagesLeft,
            showPageCounter = this[Keys.showPageCounter] ?: defaults.showPageCounter,
            showTotalPageCount = this[Keys.showTotalPageCount] ?: defaults.showTotalPageCount,
            hideFootnotes = this[Keys.hideFootnotes] ?: defaults.hideFootnotes,
            invertImages = this[Keys.invertImages] ?: defaults.invertImages,
            dropCaps = this[Keys.dropCaps] ?: defaults.dropCaps,
            pageTurnAnimation = enumOrDefault(
                this[Keys.pageTurnAnimation],
                defaults.pageTurnAnimation,
            ),
            pdfSinglePage = this[Keys.pdfSinglePage] ?: defaults.pdfSinglePage,
            pdfTheme = this[Keys.pdfTheme]?.let { value -> AppTheme.entries.firstOrNull { it.name == value } },
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
            pdfToolbarAppearance = enumOrDefault(this[Keys.pdfToolbarAppearance], defaults.pdfToolbarAppearance),
            showPdfTopBar = this[Keys.showPdfTopBar] ?: defaults.showPdfTopBar,
            showCollapsedReaderHandle = this[Keys.showCollapsedReaderHandle] ?: defaults.showCollapsedReaderHandle,
            expandReaderPanelsOnSwipe = this[Keys.expandReaderPanelsOnSwipe] ?: defaults.expandReaderPanelsOnSwipe,
            brightnessSwipeEnabled = this[Keys.brightnessSwipeEnabled] ?: defaults.brightnessSwipeEnabled,
            autoHideReturnButton = this[Keys.autoHideReturnButton] ?: defaults.autoHideReturnButton,
            showReaderLoadHud = this[Keys.showReaderLoadHud] ?: defaults.showReaderLoadHud,
            defaultTranslator = this[Keys.defaultTranslator],
            defaultDictionary = this[Keys.defaultDictionary],
            defaultSearchApp = this[Keys.defaultSearchApp],
            appLock = this[Keys.appLock] ?: defaults.appLock,
            appLockDelay = enumOrDefault(this[Keys.appLockDelay], defaults.appLockDelay),
            backupMode = enumOrDefault(this[Keys.backupMode], defaults.backupMode),
            autoInvertImages = this[Keys.autoInvertImages] ?: defaults.autoInvertImages,
            dailyGoalMinutes = this[Keys.dailyGoal] ?: defaults.dailyGoalMinutes,
            viewMode = enumOrDefault(this[Keys.libraryViewMode], defaults.viewMode),
            profileName = this[Keys.profileName] ?: defaults.profileName,
            profileAvatarBase64 = this[Keys.profileAvatar],
            readingLogSort = this[Keys.readingLogSort] ?: defaults.readingLogSort,
            profileSearchCategoryOrder = this[Keys.profileSearchCategoryOrder]
                ?.split(',')
                ?.mapNotNull { token ->
                    val trimmed = token.trim()
                    ProfileSearchCategory.entries.firstOrNull { it.name == trimmed }
                }
                ?.let(ProfileSearchCategory::normalizeOrder)
                ?: defaults.profileSearchCategoryOrder,
            profileSearchHiddenCategories = this[Keys.profileSearchHiddenCategories]
                ?.split(',')
                ?.mapNotNull { token ->
                    val trimmed = token.trim()
                    ProfileSearchCategory.entries.firstOrNull { it.name == trimmed }
                }
                ?.toSet()
                .orEmpty(),
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
