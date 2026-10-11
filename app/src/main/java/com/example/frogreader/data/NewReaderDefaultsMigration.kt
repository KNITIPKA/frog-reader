package com.example.frogreader.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Preserve untouched legacy preferences while applying the new defaults only to fresh installs. */
internal class NewReaderDefaultsMigration(
    private val hasExistingInstall: () -> Boolean = { false },
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean = !currentData.contains(marker)

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (currentData.hasSavedUserPreferences() || hasExistingInstall()) {
                val reader = LegacySettingsDefaults.reader
                val app = LegacySettingsDefaults.app
                putIfAbsent(booleanPreferencesKey("justify"), reader.justify)
                putIfAbsent(booleanPreferencesKey("hyphenation"), reader.hyphenation)
                putIfAbsent(booleanPreferencesKey("book_styles"), reader.bookStyles)
                putIfAbsent(stringPreferencesKey("page_margins"), reader.pageMargins.name)
                putIfAbsent(stringPreferencesKey("reading_mode"), reader.readingMode.name)
                putIfAbsent(booleanPreferencesKey("drop_caps"), reader.dropCaps)
                putIfAbsent(stringPreferencesKey("page_turn_animation"), reader.pageTurnAnimation.name)
                putIfAbsent(stringPreferencesKey("light_theme_default"), app.lightThemeDefault.name)
                putIfAbsent(booleanPreferencesKey("auto_invert_images"), app.autoInvertImages)
            }
            this[marker] = true
        }.toPreferences()

    override suspend fun cleanUp() = Unit

    companion object {
        val marker = booleanPreferencesKey("new_reader_defaults_v1")
    }
}

/** A fresh DataStore contains the migration marker before Welcome checks for prior use. */
internal fun Preferences.hasSavedUserPreferences(): Boolean =
    asMap().keys.any { it != NewReaderDefaultsMigration.marker }

private fun <T> MutablePreferences.putIfAbsent(key: Preferences.Key<T>, value: T) {
    if (!contains(key)) this[key] = value
}
