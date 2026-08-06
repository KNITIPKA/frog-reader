package com.example.frogreader.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@kotlinx.serialization.Serializable
enum class ReaderFont { LITERATA, SERIF, SANS, CUSTOM }
@kotlinx.serialization.Serializable
enum class ReadingMode { SCROLL, PAGES }

/** Horizontal padding of the reading column. */
@kotlinx.serialization.Serializable
enum class PageMargins { NARROW, NORMAL, WIDE }

/** Page-turn animation style (visual stubs for now — only SLIDE is wired). */
@kotlinx.serialization.Serializable
enum class PageTurnAnimation { SLIDE, CASCADE, PAGE_CURL }

/** One theme for the whole app (every screen and the reading surface). */
enum class AppTheme { WHITE, SEPIA, OLED }

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
    val justify: Boolean = true,
    val hyphenation: Boolean = true,
    /**
     * Render the book the way its publisher designed it: embedded fonts,
     * the book's own alignment, indents, line spacing and hyphenation
     * (where the file actually specifies them) win over the user settings.
     */
    val bookStyles: Boolean = false,
    val pageMargins: PageMargins = PageMargins.NORMAL,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    /** Every chapter begins on a fresh page (paged mode). */
    val startChaptersOnNewPage: Boolean = true,
    /** Page footer counts pages left in the chapter instead of the book. */
    val showChapterPagesLeft: Boolean = false,
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
    val dropCaps: Boolean = false,
    /** Chosen page-turn animation (UI stub until animations are built). */
    val pageTurnAnimation: PageTurnAnimation = PageTurnAnimation.SLIDE,
)

/** App-wide settings (theme, feedback, behavior, privacy). */
data class AppSettings(
    val theme: AppTheme = AppTheme.SEPIA,
    val haptics: Boolean = true,
    val keepScreenOn: Boolean = true,
    val volumeKeyPaging: Boolean = true,
    val appLock: Boolean = false,
    /** Automatically invert images when OLED theme is active. */
    val autoInvertImages: Boolean = true,
    /** Daily reading goal in minutes (for the stats screen). */
    val dailyGoalMinutes: Int = 30,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
)

private val Context.settingsDataStore by preferencesDataStore(name = "reader_settings")

class SettingsRepository(private val context: Context) {

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
        val hideFootnotes = booleanPreferencesKey("hide_footnotes")
        val invertImages = booleanPreferencesKey("invert_images")
        val dropCaps = booleanPreferencesKey("drop_caps")
        val pageTurnAnimation = stringPreferencesKey("page_turn_animation")

        // v2: beige became the default; the key bump applies it to installs
        // that still carry the old default in the v1 key.
        val appTheme = stringPreferencesKey("app_theme_v2")
        val haptics = booleanPreferencesKey("haptics")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val volumeKeyPaging = booleanPreferencesKey("volume_key_paging")
        val appLock = booleanPreferencesKey("app_lock")
        val autoInvertImages = booleanPreferencesKey("auto_invert_images")
        val dailyGoal = androidx.datastore.preferences.core.intPreferencesKey("daily_goal_minutes")
        val libraryViewMode = stringPreferencesKey("library_view_mode")
    }

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
            prefs[Keys.haptics] = updated.haptics
            prefs[Keys.keepScreenOn] = updated.keepScreenOn
            prefs[Keys.volumeKeyPaging] = updated.volumeKeyPaging
            prefs[Keys.appLock] = updated.appLock
            prefs[Keys.autoInvertImages] = updated.autoInvertImages
            prefs[Keys.dailyGoal] = updated.dailyGoalMinutes
            prefs[Keys.libraryViewMode] = updated.viewMode.name
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
            haptics = this[Keys.haptics] ?: defaults.haptics,
            keepScreenOn = this[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            volumeKeyPaging = this[Keys.volumeKeyPaging] ?: defaults.volumeKeyPaging,
            appLock = this[Keys.appLock] ?: defaults.appLock,
            autoInvertImages = this[Keys.autoInvertImages] ?: defaults.autoInvertImages,
            dailyGoalMinutes = this[Keys.dailyGoal] ?: defaults.dailyGoalMinutes,
            viewMode = enumOrDefault(this[Keys.libraryViewMode], defaults.viewMode),
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    companion object {
        private const val BOOT_PREFS = "boot_hints"
        private const val KEY_THEME = "app_theme"

        /**
         * The theme the app was last running in, readable synchronously.
         *
         * DataStore's first read is disk I/O on a background dispatcher, so the
         * first frame after a cold start would otherwise have to be painted in
         * the default theme and corrected a moment later. On a Midnight install
         * that means a beige screen flashing black. This mirror is written from
         * the UI whenever the effective theme is known, which also heals an
         * install that has never seen this code.
         */
        fun bootTheme(context: Context): AppTheme {
            val name = context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, null)
            return name?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SEPIA
        }

        fun rememberBootTheme(context: Context, theme: AppTheme) {
            if (bootTheme(context) == theme) return
            context.getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_THEME, theme.name)
                .apply()
        }
    }
}
