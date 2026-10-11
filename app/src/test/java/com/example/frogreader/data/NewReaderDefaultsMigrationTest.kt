package com.example.frogreader.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewReaderDefaultsMigrationTest {
    private val marker = NewReaderDefaultsMigration.marker
    private val justify = booleanPreferencesKey("justify")
    private val hyphenation = booleanPreferencesKey("hyphenation")
    private val bookStyles = booleanPreferencesKey("book_styles")
    private val margins = stringPreferencesKey("page_margins")
    private val readingMode = stringPreferencesKey("reading_mode")
    private val dropCaps = booleanPreferencesKey("drop_caps")
    private val animation = stringPreferencesKey("page_turn_animation")
    private val lightTheme = stringPreferencesKey("light_theme_default")
    private val autoInvertImages = booleanPreferencesKey("auto_invert_images")

    @Test
    fun `fresh preferences receive only the marker and still qualify for welcome`() = runTest {
        val migration = NewReaderDefaultsMigration()
        val fresh = emptyPreferences()

        assertFalse(fresh.hasSavedUserPreferences())
        assertTrue(migration.shouldMigrate(fresh))

        val migrated = migration.migrate(fresh)

        assertEquals(preferencesOf(marker to true), migrated)
        assertFalse(migrated.hasSavedUserPreferences())
        assertFalse(migration.shouldMigrate(migrated))
    }

    @Test
    fun `migration markers do not count as saved user preferences`() {
        listOf(false, true).forEach { markerValue ->
            assertFalse(preferencesOf(marker to markerValue).hasSavedUserPreferences())
        }
        val unrelated = stringPreferencesKey("future_user_preference")
        assertTrue(preferencesOf(unrelated to "saved").hasSavedUserPreferences())
        assertTrue(preferencesOf(marker to true, justify to false).hasSavedUserPreferences())
    }

    @Test
    fun `existing sparse preferences retain their saved value and receive legacy defaults`() = runTest {
        val fontSize = floatPreferencesKey("font_size_sp")
        val original = preferencesOf(fontSize to 24f)
        val migrated = NewReaderDefaultsMigration().migrate(original)

        assertLegacyDefaults(migrated)
        assertEquals(24f, migrated[fontSize])
        assertEquals(true, migrated[marker])
        assertTrue(migrated.hasSavedUserPreferences())
        assertEquals(original.asMap().size + 10, migrated.asMap().size)
        assertEquals(preferencesOf(fontSize to 24f), original)
    }

    @Test
    fun `external existing installation preserves legacy defaults even with empty preferences`() = runTest {
        var historyChecks = 0
        val migration = NewReaderDefaultsMigration {
            historyChecks++
            true
        }

        val migrated = migration.migrate(emptyPreferences())

        assertLegacyDefaults(migrated)
        assertEquals(true, migrated[marker])
        assertTrue(migrated.hasSavedUserPreferences())
        assertEquals(10, migrated.asMap().size)
        assertEquals(1, historyChecks)
    }

    @Test
    fun `explicit booleans enum identifiers and unrelated preferences survive unchanged`() = runTest {
        val unrelated = stringPreferencesKey("future_user_preference")
        val original = preferencesOf(
            justify to false,
            hyphenation to false,
            bookStyles to true,
            margins to "WIDE",
            readingMode to "PAGES",
            dropCaps to true,
            animation to "FUTURE_ANIMATION",
            lightTheme to "BEIGE",
            autoInvertImages to false,
            unrelated to "keep exactly",
        )

        val migrated = NewReaderDefaultsMigration { true }.migrate(original)

        val expected = original.toMutablePreferences().apply { this[marker] = true }
        assertEquals(expected, migrated)
        assertEquals(false, migrated[justify])
        assertEquals(false, migrated[hyphenation])
        assertEquals(false, migrated[autoInvertImages])
        assertEquals("FUTURE_ANIMATION", migrated[animation])
        assertEquals("keep exactly", migrated[unrelated])
    }

    @Test
    fun `marker presence completes migration and legacy preservation is idempotent`() = runTest {
        val migration = NewReaderDefaultsMigration()
        val original = preferencesOf(justify to false)

        assertTrue(migration.shouldMigrate(original))
        val migrated = migration.migrate(original)
        assertFalse(migration.shouldMigrate(migrated))
        assertEquals(migrated, migration.migrate(migrated))
        assertEquals(false, migrated[justify])
        listOf(false, true).forEach { markerValue ->
            assertFalse(migration.shouldMigrate(preferencesOf(marker to markerValue)))
        }
    }

    @Test
    fun `completed fresh migration does not rerun after user preferences are saved`() = runTest {
        val migration = NewReaderDefaultsMigration { error("Completed migration must not inspect install history") }
        val freshCompleted = preferencesOf(marker to true)
        val savedLater = freshCompleted.toMutablePreferences().apply {
            this[justify] = false
            this[animation] = "CASCADE"
        }

        assertFalse(migration.shouldMigrate(freshCompleted))
        assertFalse(migration.shouldMigrate(savedLater))
        assertTrue(savedLater.hasSavedUserPreferences())
        assertEquals(preferencesOf(marker to true, justify to false, animation to "CASCADE"), savedLater)
        assertFalse(savedLater.contains(hyphenation))
        assertFalse(savedLater.contains(readingMode))
    }

    private fun assertLegacyDefaults(preferences: Preferences) {
        assertEquals(true, preferences[justify])
        assertEquals(true, preferences[hyphenation])
        assertEquals(false, preferences[bookStyles])
        assertEquals("NORMAL", preferences[margins])
        assertEquals("SCROLL", preferences[readingMode])
        assertEquals(false, preferences[dropCaps])
        assertEquals("SLIDE", preferences[animation])
        assertEquals("LIGHT", preferences[lightTheme])
        assertEquals(true, preferences[autoInvertImages])
    }
}
